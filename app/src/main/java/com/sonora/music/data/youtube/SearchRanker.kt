package com.sonora.music.data.youtube

import com.sonora.music.data.model.Song
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.text.Normalizer
import kotlin.math.log10

/**
 * Re-ranks merged YouTube Music + YouTube results for a music app:
 * how well the title/artist match your words matters most, then clean audio sources,
 * then YouTube's own order and popularity. Shorts, live streams and unwanted
 * covers / remixes / reactions are pushed down.
 * Works with any script (Khmer, Thai, Korean…) because tokens are Unicode letters/digits.
 */
internal class SearchRanker(query: String) {

    private val q = normalize(query)
    private val tokens = tokenize(q)
    private val wantsVariant = VARIANT_WORDS.filter { it in tokens || q.contains(it) }.toSet()
    private val wantsLong = LONG_WORDS.any { q.contains(it) }

    fun score(item: StreamInfoItem, position: Int, isMusicSong: Boolean): Double {
        val title = normalize(item.name.orEmpty())
        val artist = normalize(item.uploaderName.orEmpty().removeSuffix(" - Topic"))
        val haystack = "$title $artist"
        var score = 0.0

        // 1. Word coverage: how many of your words appear in title/artist
        if (tokens.isNotEmpty()) {
            val hits = tokens.count { haystack.contains(it) }
            score += 60.0 * hits / tokens.size
            if (hits == 0) score -= 30
        }
        // 2. Exact phrase / artist matches
        if (q.length >= 3 && title.contains(q)) score += 25
        if (q.length >= 3 && haystack.contains(q)) score += 8
        if (artist.isNotBlank() && artist.length >= 3 && q.contains(artist)) score += 15
        if (title == q) score += 10

        // 3. Source quality
        if (isMusicSong) score += 14
        if (item.isUploaderVerified) score += 4
        if (item.uploaderName.orEmpty().endsWith(" - Topic")) score += 6
        if (!isMusicSong) {
            if (AUDIO_HINTS.any { title.contains(it) }) score += 5
            if (VIDEO_HINTS.any { title.contains(it) }) score += 3
        }

        // 4. Penalties
        val duration = item.duration
        if (item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM) score -= 60
        if (runCatching { item.isShortFormContent }.getOrDefault(false) || duration in 1..59) score -= 45
        if (duration > 20 * 60 && !wantsLong) score -= 18
        VARIANT_WORDS.forEach { w -> if (w !in wantsVariant && title.containsWord(w)) score -= 12 }
        JUNK_WORDS.forEach { w -> if (title.containsWord(w) && !q.contains(w)) score -= 25 }

        // 5. YouTube's own relevance order + popularity, as tie-breakers
        score -= position * 0.6
        if (item.viewCount > 0) score += log10(item.viewCount.toDouble()) * 1.2

        return score
    }

    companion object {
        const val MIN_SCORE = -40.0

        private val AUDIO_HINTS = listOf("official audio", "audio", "lyrics", "lyric video")
        private val VIDEO_HINTS = listOf("official music video", "official video", "official mv", " mv")
        private val VARIANT_WORDS = listOf("cover", "remix", "karaoke", "instrumental", "slowed", "reverb", "sped up", "8d", "nightcore", "live", "acoustic", "piano")
        private val JUNK_WORDS = listOf("reaction", "reacts", "tutorial", "how to play", "behind the scenes", "interview", "trailer", "teaser")
        private val LONG_WORDS = listOf("mix", "playlist", "full album", "nonstop", "non stop", "compilation", "hour", "megamix", "medley")

        private val WORD = Regex("[\\p{L}\\p{N}\\p{M}]+")
        private val BRACKETS = Regex("[\\(\\[].*?[\\)\\]]")

        fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKC)
                .replace('’', '\'')
                .replace(Regex("\\s+"), " ")
                .trim()

        fun tokenize(s: String): List<String> =
            WORD.findAll(s).map { it.value }.filter { it.length > 1 || it.any { c -> c.code > 0x2FF } }.distinct().toList()

        /** Same track uploaded twice ("Song (Official Audio)" vs "Song [Lyrics]") collapses to one. */
        fun dedupeKey(song: Song): String {
            val title = normalize(song.title).replace(BRACKETS, "")
            return tokenize(title).joinToString(" ") + "|" + normalize(song.artist.removeSuffix(" - Topic"))
        }

        private fun String.containsWord(w: String): Boolean =
            if (w.contains(' ')) contains(w) else WORD.findAll(this).any { it.value == w }
    }
}
