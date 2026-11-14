package com.sonora.music.data.lyrics

import androidx.compose.runtime.Immutable
import com.sonora.music.AppGraph
import com.sonora.music.data.db.LyricsDao
import com.sonora.music.data.db.LyricsEntity
import com.sonora.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

@Immutable
data class LyricLine(val timeMs: Long, val text: String)

@Immutable
data class Lyrics(
    /** Time-synced lines (karaoke style). Empty if only plain text is available. */
    val lines: List<LyricLine>,
    val plain: String?,
    val source: String,
) {
    val isSynced: Boolean get() = lines.isNotEmpty()
}

/**
 * Lyrics from LRCLIB (free, time-synced) with YouTube Music as a fallback.
 * Results — including "not found" — are cached in Room so they show instantly
 * next time and work offline for songs you've played before.
 */
class LyricsRepository(private val client: OkHttpClient, private val dao: LyricsDao) {

    suspend fun get(song: Song, durationSec: Long, forceRefresh: Boolean = false): Lyrics? = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            dao.get(song.id)?.let { cached ->
                val fresh = System.currentTimeMillis() - cached.fetchedAt < NOT_FOUND_TTL
                if (cached.source != NONE) return@withContext cached.toLyrics()
                if (fresh) return@withContext null
            }
        }
        val title = cleanTitle(song.title)
        val artist = cleanArtist(song.artist, song.title)
        val duration = durationSec.takeIf { it > 0 } ?: song.durationSec

        val found: LyricsEntity? =
            runCatching { lrclibGet(title, artist, duration) }.getOrNull()?.toEntity(song.id)
                ?: runCatching { lrclibSearch(title, artist, duration) }.getOrNull()?.toEntity(song.id)
                ?: runCatching { AppGraph.ytMusic.lyrics(song.id) }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { LyricsEntity(song.id, null, it, "YouTube Music", System.currentTimeMillis()) }

        dao.put(found ?: LyricsEntity(song.id, null, null, NONE, System.currentTimeMillis()))
        found?.toLyrics()
    }

    // ------------------------------------------------------------------ LRCLIB

    private fun lrclibGet(title: String, artist: String, duration: Long): JSONObject? {
        if (title.isBlank()) return null
        val url = "$LRCLIB/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .apply { if (duration > 0) addQueryParameter("duration", duration.toString()) }
            .build()
        val body = fetch(url.toString()) ?: return null
        return JSONObject(body).takeIf { it.hasLyrics() }
    }

    private fun lrclibSearch(title: String, artist: String, duration: Long): JSONObject? {
        if (title.isBlank()) return null
        val attempts = listOf(
            "$LRCLIB/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", title)
                .addQueryParameter("artist_name", artist).build(),
            "$LRCLIB/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", "$title $artist".trim()).build(),
            "$LRCLIB/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", title).build(),
        )
        for (url in attempts) {
            val body = fetch(url.toString()) ?: continue
            val arr = JSONArray(body)
            val candidates = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .filter { it.hasLyrics() }
                // Same song length (±4 s) when we know it; otherwise anything goes.
                .filter { duration <= 0 || it.optDouble("duration", 0.0) <= 0 || abs(it.optDouble("duration") - duration) <= 4 }
            val best = candidates.maxByOrNull { c ->
                var score = 0.0
                if (!c.optString("syncedLyrics").isNullOrBlank() && c.optString("syncedLyrics") != "null") score += 10
                if (c.optString("artistName").equals(artist, ignoreCase = true)) score += 5
                if (c.optString("trackName").equals(title, ignoreCase = true)) score += 5
                if (duration > 0) score -= abs(c.optDouble("duration", duration.toDouble()) - duration)
                score
            }
            if (best != null) return best
        }
        return null
    }

    private fun fetch(url: String): String? {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Sonora (https://github.com/KheangLov/sonora)")
            .build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            return r.body?.string()
        }
    }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.hasLyrics(): Boolean = str("syncedLyrics") != null || str("plainLyrics") != null

    private fun JSONObject.toEntity(id: String) =
        LyricsEntity(id, str("syncedLyrics"), str("plainLyrics"), "LRCLIB", System.currentTimeMillis())

    private fun LyricsEntity.toLyrics(): Lyrics? {
        val lines = synced?.let(::parseLrc).orEmpty()
        val text = plain ?: if (lines.isNotEmpty()) lines.joinToString("\n") { it.text } else null
        if (lines.isEmpty() && text.isNullOrBlank()) return null
        return Lyrics(lines, text, source)
    }

    companion object {
        private const val LRCLIB = "https://lrclib.net"
        private const val NONE = "none"
        /** Retry songs that had no lyrics after 3 days — they may have been added since. */
        private const val NOT_FOUND_TTL = 3L * 24 * 60 * 60 * 1000

        private val TIME_TAG = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

        /** Parses "[01:02.34]line" (also multiple tags per line). Skips metadata like [ar:…]. */
        fun parseLrc(lrc: String): List<LyricLine> {
            val out = ArrayList<LyricLine>()
            lrc.lineSequence().forEach { raw ->
                val tags = TIME_TAG.findAll(raw).toList()
                if (tags.isEmpty()) return@forEach
                val text = raw.substring(tags.last().range.last + 1).trim()
                tags.forEach { m ->
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].toLong()
                    val frac = m.groupValues[3]
                    val ms = when (frac.length) {
                        0 -> 0L
                        1 -> frac.toLong() * 100
                        2 -> frac.toLong() * 10
                        else -> frac.take(3).toLong()
                    }
                    out += LyricLine(min * 60_000 + sec * 1000 + ms, text)
                }
            }
            return out.sortedBy { it.timeMs }
        }

        private val NOISE = Regex(
            "(?i)\\s*[(\\[][^)\\]]*(official|video|audio|lyric|lyrics|visualizer|mv|m/v|hd|4k|remaster|live|explicit|clean|prod\\.)[^)\\]]*[)\\]]"
        )
        private val FEAT = Regex("(?i)\\s*[(\\[]?\\s*(feat\\.?|ft\\.?|featuring)\\s.*$")

        fun cleanTitle(title: String): String {
            var t = title.replace(NOISE, "")
            // "Artist - Song" uploads: keep the song part
            if (" - " in t) t = t.substringAfter(" - ")
            t = t.replace(FEAT, "")
            return t.replace(Regex("\\s+"), " ").trim().trim('-', '|', ' ')
        }

        fun cleanArtist(artist: String, title: String): String {
            var a = artist.removeSuffix(" - Topic").removeSuffix("VEVO").trim()
            // Uploads by channels: "Artist - Song" puts the real artist in the title
            if (" - " in title && (a.isBlank() || a.contains("records", true) || a.contains("music", true))) {
                a = title.substringBefore(" - ")
            }
            return a.split(",", "&", " x ", " X ", " feat", " ft.").first().trim()
        }
    }
}
