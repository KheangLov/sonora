package com.sonora.music.data.filter

import com.sonora.music.data.model.HomeSection
import com.sonora.music.data.model.Song
import com.sonora.music.data.youtube.YtCollection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.Normalizer

/**
 * Decides what never shows up in the app:
 *  1. Anything Thai / Thailand-related (always on): Thai script, Thai keywords, Thai labels and artists.
 *     Applied to search queries, suggestions, results, home feed, radio/autoplay and YouTube Music lists.
 *  2. Songs you disliked (kept in the database, manageable from Library → Disliked songs).
 */
object ContentFilter {

    // ------------------------------------------------------------------ dislikes

    private val _disliked = MutableStateFlow<Set<String>>(emptySet())
    val dislikedIds: StateFlow<Set<String>> = _disliked.asStateFlow()

    fun start(scope: CoroutineScope, ids: Flow<List<String>>) {
        scope.launch { ids.collect { _disliked.value = it.toHashSet() } }
    }

    fun isDisliked(id: String): Boolean = id in _disliked.value

    // ------------------------------------------------------------------ public checks

    /** True if this song must not be shown or played. */
    fun blocks(song: Song): Boolean =
        song.id in _disliked.value || isThai(song.title) || isThaiArtist(song.artist) || isThai(song.artist)

    fun blocks(c: YtCollection): Boolean = isThai(c.title) || isThai(c.subtitle) || isThaiArtist(c.subtitle)

    /** Search / suggestion text the user typed or YouTube suggested. */
    fun blocksQuery(query: String): Boolean = isThai(query) || isThaiArtist(query)

    fun filterSongs(songs: List<Song>): List<Song> = songs.filterNot(::blocks)

    fun filterSection(section: HomeSection): HomeSection? {
        if (isThai(section.title) || isThai(section.subtitle.orEmpty())) return null
        val s = section.copy(
            songs = filterSongs(section.songs),
            collections = section.collections.filterNot(::blocks),
        )
        return s.takeIf { it.songs.isNotEmpty() || it.collections.isNotEmpty() }
    }

    fun filterSections(sections: List<HomeSection>): List<HomeSection> = sections.mapNotNull(::filterSection)

    // ------------------------------------------------------------------ Thai detection

    /** Thai Unicode block (U+0E00–U+0E7F). Khmer, Lao etc. are separate blocks and unaffected. */
    private val THAI_SCRIPT = Regex("[\\u0E00-\\u0E7F]")

    /** Whole-word / phrase keywords, matched case-insensitively. */
    private val THAI_WORDS = listOf(
        "thai", "thais", "thailand", "thailande", "tailandia", "thaïlande", "ประเทศไทย",
        "bangkok", "pattaya", "phuket", "chiang mai", "chiangmai", "isaan", "isan song",
        "t-pop", "tpop", "t pop", "thai pop", "thaipop", "thai rap", "thai song", "thai songs",
        "luk thung", "lukthung", "luk thoong", "luk krung", "lukkrung", "mor lam", "morlam", "molam",
        "lakorn", "lakhon", "thai bl", "thai drama", "thai series", "thai ost",
        // labels, TV channels, idol groups
        "gmmtv", "gmm tv", "gmm grammy", "gmmgrammy", "grammy gold", "gmm25", "gmm 25",
        "genie records", "genierock", "genie rock", "rsiam", "rs music", "spicy disc", "smallroom",
        "what the duck", "muzik move", "kamikaze records", "loveis entertainment", "loveis",
        "boxx music", "chandelier music", "bakery music", "wayfer records", "high cloud entertainment",
        "workpoint", "one31", "ch3thailand", "ch3 thailand", "ch7hd", "thairath", "amarin tv",
        "bnk48", "cgm48", "4eve", "proxie", "pixxie", "lyra thailand", "atlas thailand",
    )

    /** Thai artists who publish under Latin names (matched against artist / channel names). */
    private val THAI_ARTISTS = listOf(
        "bodyslam", "tilly birds", "three man down", "jeff satur", "billkin", "pp krit", "palmy",
        "polycat", "silly fools", "lipta", "getsunova", "labanoon", "ink waruntorn", "violette wautier",
        "sprite x guygeegee", "guygeegee", "f.hero", "fhero", "youngohm", "young ohm", "lazyloxy",
        "jaonaay", "gena desouza", "pchy", "nont tanont", "joey phuwasit", "zom marie", "atom chanakan",
        "bowkylion", "da endorphine", "stamp apiwat", "bird thongchai", "tata young",
        "safeplanet", "ebola band", "retrospect band", "klear band",
        "potato band", "cocktail band", "big ass band", "lula kanyarat", "scrubb", "the yers",
        "gemini norawit", "fourth nattawat", "bright vachirawit", "win metawin", "mew suppasit",
        "tay tawan", "nanon korapat", "ohm pawat", "earth pirapat", "mix sahaphap", "zee pruk",
        "nunew", "jeff satur", "boun noppanut", "prem warut", "off jumpol", "gun atthaphan",
        "milli thailand", "1mill", "thaiboy digital", "maiyarap", "twopee southside", "diamond mqt",
        "urboytj", "ally pattarasorn", "mean band", "num kala", "paowalee", "takkatan chollada",
        "lamyai haithongkham", "tai orathai", "mike piromporn", "got jakrapun", "phai pongsathorn",
        "jintara poonlarp", "pumpuang duangjan", "siriporn ampaipong", "yinglee srijumpol",
    )

    private val WORD_CHARS = "\\p{L}\\p{N}\\p{M}"
    private val THAI_WORDS_RX = THAI_WORDS.map(::wordRegex)
    private val THAI_ARTISTS_RX = THAI_ARTISTS.distinct().map(::wordRegex)

    private fun wordRegex(phrase: String): Regex =
        Regex("(?<![$WORD_CHARS])" + Regex.escape(phrase) + "(?![$WORD_CHARS])")

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKC).replace(Regex("\\s+"), " ").trim()

    fun isThai(text: String): Boolean {
        if (text.isBlank()) return false
        if (THAI_SCRIPT.containsMatchIn(text)) return true
        val t = normalize(text)
        return THAI_WORDS_RX.any { it.containsMatchIn(t) }
    }

    private fun isThaiArtist(text: String): Boolean {
        if (text.isBlank()) return false
        val t = normalize(text)
        return THAI_ARTISTS_RX.any { it.containsMatchIn(t) }
    }
}
