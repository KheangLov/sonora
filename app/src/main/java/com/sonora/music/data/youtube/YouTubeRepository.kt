package com.sonora.music.data.youtube

import android.net.Uri
import com.sonora.music.data.model.HomeSection
import com.sonora.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

class YouTubeRepository {

    data class ResolvedAudio(
        val url: String,
        val contentLength: Long,
        val suffix: String,
        val expiresAt: Long,
    )

    private val yt get() = ServiceList.YouTube
    private val audioCache = ConcurrentHashMap<String, ResolvedAudio>()
    private val relatedCache = ConcurrentHashMap<String, List<Song>>()
    @Volatile private var homeCache: Pair<Long, List<HomeSection>>? = null

    // ---------------------------------------------------------------- search

    suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        // YouTube Music "songs" filter first: clean audio tracks with square art.
        val songs = runCatching { searchWith(query, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS) }
            .getOrDefault(emptyList())
        songs.ifEmpty { searchWith(query, YoutubeSearchQueryHandlerFactory.VIDEOS) }
    }

    private fun searchWith(query: String, filter: String): List<Song> {
        val handler = yt.searchQHFactory.fromQuery(query, listOf(filter), "")
        return SearchInfo.getInfo(yt, handler).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toSongOrNull() }
            .distinctBy { it.id }
    }

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        yt.suggestionExtractor.suggestionList(query).take(8)
    }

    // ---------------------------------------------------------------- streams

    /**
     * Resolves a playable audio URL. Blocking — call from a background thread
     * (ExoPlayer loader thread or Dispatchers.IO).
     */
    fun resolveAudio(id: String, forceFresh: Boolean = false): ResolvedAudio {
        if (!forceFresh) {
            audioCache[id]?.takeIf { it.expiresAt > System.currentTimeMillis() + 60_000 }?.let { return it }
        }
        val info = StreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=$id")
        val stream = info.audioStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { it.score() }
            ?: throw IOException("No audio stream available for $id")

        val url = stream.content
        val uri = Uri.parse(url)
        val resolved = ResolvedAudio(
            url = url,
            contentLength = uri.getQueryParameter("clen")?.toLongOrNull() ?: -1L,
            suffix = stream.format?.suffix ?: "m4a",
            expiresAt = uri.getQueryParameter("expire")?.toLongOrNull()?.times(1000)
                ?: (System.currentTimeMillis() + 5 * 60 * 60 * 1000L),
        )
        audioCache[id] = resolved
        relatedCache[id] = info.relatedItems.filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toSongOrNull() }
            .distinctBy { it.id }
        return resolved
    }

    fun invalidate(id: String) {
        audioCache.remove(id)
    }

    private fun AudioStream.score(): Int {
        val br = if (averageBitrate > 0) averageBitrate else bitrate
        return br.coerceAtLeast(0)
    }

    /** "Radio" — songs YouTube considers related. Powers autoplay. */
    suspend fun related(id: String): List<Song> = withContext(Dispatchers.IO) {
        relatedCache[id] ?: run {
            resolveAudio(id)
            relatedCache[id].orEmpty()
        }
    }

    // ---------------------------------------------------------------- home feed

    private class Spec(val title: String, val subtitle: String?, val load: suspend () -> List<Song>)

    suspend fun home(seed: Song?, force: Boolean = false): List<HomeSection> {
        homeCache?.let { (ts, data) ->
            if (!force && System.currentTimeMillis() - ts < 30 * 60 * 1000L && data.isNotEmpty()) return data
        }
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val cambodia = TimeZone.getDefault().id == "Asia/Phnom_Penh" || Locale.getDefault().country == "KH"

        val specs = buildList {
            if (seed != null) {
                add(Spec("Quick picks", "Because you played ${seed.title}") { related(seed.id) })
            } else {
                add(Spec("Quick picks", "Start listening") { search("top hits this week") })
            }
            add(Spec("Trending now", "What everyone's playing") { search("trending songs $year") })
            if (cambodia) add(Spec("Khmer hits", "Fresh from Cambodia") { search("khmer new songs $year") })
            add(Spec("Viral right now", null) { search("viral songs $year") })
            add(Spec("Chill vibes", "Slow down for a bit") { search("chill vibes songs") })
            add(Spec("Feel-good pop", null) { search("feel good pop hits") })
            add(Spec("Hip-hop heat", null) { search("hip hop hits $year") })
            add(Spec("Throwback", "Hits you still know every word to") { search("2010s throwback hits") })
        }

        val sections = coroutineScope {
            specs.map { spec ->
                async {
                    runCatching { spec.load() }.getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { HomeSection(spec.title, spec.subtitle, it.take(20)) }
                }
            }.awaitAll().filterNotNull()
        }
        if (sections.isNotEmpty()) homeCache = System.currentTimeMillis() to sections
        return sections
    }

    // ---------------------------------------------------------------- mapping

    private fun StreamInfoItem.toSongOrNull(): Song? {
        val id = runCatching { yt.streamLHFactory.getId(url) }.getOrNull() ?: return null
        return Song(
            id = id,
            title = name.orEmpty(),
            artist = uploaderName.orEmpty().removeSuffix(" - Topic"),
            thumbnail = bestThumbnail(thumbnails, id),
            durationSec = duration.coerceAtLeast(0),
        )
    }

    private fun bestThumbnail(images: List<Image>, id: String): String {
        val best = images.maxByOrNull { it.width.toLong() * it.height.toLong() }?.url
            ?: return "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        // YouTube Music art is served at tiny sizes by default — request a crisp one.
        return if ("googleusercontent.com" in best || "ggpht.com" in best) {
            best.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
        } else best
    }
}
