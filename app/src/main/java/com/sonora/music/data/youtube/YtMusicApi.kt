package com.sonora.music.data.youtube

import androidx.compose.runtime.Immutable
import com.sonora.music.data.account.AccountStore
import com.sonora.music.data.model.HomeSection
import com.sonora.music.data.model.Song
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/** A playlist / album / artist / mix on YouTube Music that can be opened as a song list. */
@Immutable
data class YtCollection(
    val id: String,
    /** true = a radio/mix playlistId (loaded via "next"), false = a browseId page. */
    val isQueue: Boolean,
    val title: String,
    val subtitle: String,
    val thumbnail: String,
)

/**
 * Minimal signed-in YouTube Music (InnerTube WEB_REMIX) client for personalised content:
 * home feed, liked music, library playlists, and opening any playlist / album / mix.
 * Playback itself still goes through NewPipeExtractor.
 */
class YtMusicApi(private val client: OkHttpClient, private val account: AccountStore) {

    // --------------------------------------------------------------- public

    fun home(): List<HomeSection> {
        var response = post("browse", JSONObject().put("browseId", "FEmusic_home"))
        val sections = parseShelves(response).toMutableList()
        repeat(2) {
            val token = findContinuation(response) ?: return@repeat
            response = postContinuation(token)
            sections += parseShelves(response)
        }
        return sections.filter { it.songs.isNotEmpty() || it.collections.isNotEmpty() }
    }

    fun likedMusic(): YtCollection = YtCollection("VLLM", false, "Liked music", "From your YouTube account", "")

    fun libraryPlaylists(): List<YtCollection> {
        val response = post("browse", JSONObject().put("browseId", "FEmusic_liked_playlists"))
        return findAllOf(response, setOf("musicTwoRowItemRenderer", "musicResponsiveListItemRenderer"))
            .mapNotNull { (key, obj) -> if (key == "musicTwoRowItemRenderer") parseTwoRow(obj) else parseResponsive(obj) }
            .filterIsInstance<YtCollection>()
            .filter { it.id.startsWith("VL") && it.id != "VLLM" }
            .distinctBy { it.id }
    }

    fun collectionSongs(collection: YtCollection, maxPages: Int = 10): List<Song> {
        val out = LinkedHashMap<String, Song>()
        if (collection.isQueue) {
            val response = post(
                "next",
                JSONObject()
                    .put("playlistId", collection.id)
                    .put("enablePersistentPlaylistPanel", true)
                    .put("isAudioOnly", true),
            )
            collectSongs(response, out)
        } else {
            var response = post("browse", JSONObject().put("browseId", collection.id))
            collectSongs(response, out)
            // Only follow the main list's continuation (not "related" shelves).
            var token = findFirstOf(response, setOf("musicPlaylistShelfRenderer", "musicShelfRenderer"))
                ?.let { findContinuation(it) }
            var pages = 0
            while (token != null && pages++ < maxPages) {
                response = postContinuation(token)
                val before = out.size
                collectSongs(response, out)
                if (out.size == before) break
                token = findContinuation(response)
            }
        }
        // Album tracks have no per-row art/artist: borrow the album's.
        val fallbackArtist = collection.subtitle.split(" • ").firstOrNull { it.isNotBlank() && it !in TYPE_WORDS }.orEmpty()
        return out.values.map { s ->
            s.copy(
                thumbnail = s.thumbnail.ifBlank { collection.thumbnail },
                artist = s.artist.ifBlank { fallbackArtist },
            )
        }
    }

    /** Returns (name, photoUrl) of the signed-in account. */
    fun accountInfo(): Pair<String?, String?> {
        val response = post("account/account_menu", JSONObject())
        val header = findFirstOf(response, setOf("activeAccountHeaderRenderer")) ?: return null to null
        return text(header.optJSONObject("accountName")).ifBlank { null } to lastThumb(header.optJSONObject("accountPhoto"))
    }

    // --------------------------------------------------------------- http

    private fun post(endpoint: String, body: JSONObject, extraQuery: String = ""): JSONObject {
        body.put(
            "context",
            JSONObject().put(
                "client",
                JSONObject()
                    .put("clientName", "WEB_REMIX")
                    .put("clientVersion", CLIENT_VERSION)
                    .put("hl", "en")
                    .put("gl", Locale.getDefault().country.ifBlank { "US" }),
            ),
        )
        val builder = Request.Builder()
            .url("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false$extraQuery")
            .post(body.toString().toRequestBody(JSON))
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("X-Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("X-Goog-AuthUser", "0")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
        account.cookie?.let { cookie ->
            builder.header("Cookie", cookie)
            sapisid(cookie)?.let { builder.header("Authorization", sapisidHash(it)) }
        }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("YouTube Music HTTP ${response.code}")
            return JSONObject(response.body?.string() ?: throw IOException("Empty response"))
        }
    }

    private fun postContinuation(token: String): JSONObject {
        val t = URLEncoder.encode(token, "UTF-8")
        return post("browse", JSONObject().put("continuation", token), "&ctoken=$t&continuation=$t&type=next")
    }

    private fun sapisid(cookie: String): String? {
        val parts = cookie.split(';').map { it.trim() }
        return parts.firstOrNull { it.startsWith("SAPISID=") }?.substringAfter('=')
            ?: parts.firstOrNull { it.startsWith("__Secure-3PAPISID=") }?.substringAfter('=')
    }

    private fun sapisidHash(sapisid: String): String {
        val ts = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1").digest("$ts $sapisid $ORIGIN".toByteArray())
        return "SAPISIDHASH ${ts}_" + digest.joinToString("") { "%02x".format(it) }
    }

    // --------------------------------------------------------------- parsing

    private fun parseShelves(response: JSONObject): List<HomeSection> =
        findAllOf(response, setOf("musicCarouselShelfRenderer", "musicImmersiveCarouselShelfRenderer")).map { (_, shelf) ->
            val headerWrap = shelf.optJSONObject("header")
            val header = headerWrap?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                ?: headerWrap?.optJSONObject("musicImmersiveHeaderRenderer")
            val songs = mutableListOf<Song>()
            val collections = mutableListOf<YtCollection>()
            shelf.optJSONArray("contents")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val entry = arr.optJSONObject(i) ?: continue
                    val parsed = entry.optJSONObject("musicResponsiveListItemRenderer")?.let(::parseResponsive)
                        ?: entry.optJSONObject("musicTwoRowItemRenderer")?.let(::parseTwoRow)
                    when (parsed) {
                        is Song -> songs += parsed
                        is YtCollection -> collections += parsed
                    }
                }
            }
            HomeSection(
                title = text(header?.optJSONObject("title")).ifBlank { "For you" },
                subtitle = text(header?.optJSONObject("strapline")).ifBlank { null },
                songs = songs.distinctBy { it.id },
                collections = collections.distinctBy { it.id },
            )
        }

    private fun collectSongs(node: Any, out: MutableMap<String, Song>) {
        findAllOf(node, setOf("musicResponsiveListItemRenderer", "playlistPanelVideoRenderer")).forEach { (key, obj) ->
            val song = if (key == "playlistPanelVideoRenderer") parsePanelVideo(obj) else parseResponsive(obj) as? Song
            if (song != null && song.id !in out) out[song.id] = song
        }
    }

    /** A list row: usually a song, sometimes a playlist/album link. */
    private fun parseResponsive(r: JSONObject): Any? {
        val flex = r.optJSONArray("flexColumns")
        fun column(i: Int) = flex?.optJSONObject(i)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")

        val title = text(column(0))
        val subtitleRuns = runs(column(1)) + runs(column(2))
        val thumb = upscale(lastThumb(r.obj("thumbnail", "musicThumbnailRenderer", "thumbnail")))
        val videoId = r.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
            .ifBlank { findString(r.optJSONObject("overlay"), "videoId") }
            .ifBlank { findString(column(0), "videoId") }

        if (videoId.isBlank()) {
            val browseId = r.obj("navigationEndpoint", "browseEndpoint")?.optString("browseId").orEmpty()
            if (browseId.isBlank() || title.isBlank()) return null
            return YtCollection(browseId, false, title, subtitleRuns.joinToString("") { it.optString("text") }, thumb)
        }
        if (title.isBlank()) return null

        val fixed = r.optJSONArray("fixedColumns")?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text")
        val duration = parseDuration(text(fixed)).takeIf { it > 0 }
            ?: subtitleRuns.map { it.optString("text") }.firstNotNullOfOrNull { parseDuration(it).takeIf { d -> d > 0 } }
            ?: 0L
        return Song(videoId, title, artistFrom(subtitleRuns), thumb, duration)
    }

    /** A card: a song/video, or a playlist / album / artist / mix. */
    private fun parseTwoRow(r: JSONObject): Any? {
        val title = text(r.optJSONObject("title"))
        if (title.isBlank()) return null
        val subtitleRuns = runs(r.optJSONObject("subtitle"))
        val subtitle = subtitleRuns.joinToString("") { it.optString("text") }
        val thumb = upscale(lastThumb(r.obj("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail")))
        val nav = r.optJSONObject("navigationEndpoint")

        nav?.optJSONObject("watchEndpoint")?.optString("videoId")?.takeIf { it.isNotBlank() }?.let {
            return Song(it, title, artistFrom(subtitleRuns), thumb, 0)
        }
        nav?.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId")?.takeIf { it.isNotBlank() }?.let {
            return YtCollection(it, true, title, subtitle, thumb)
        }
        nav?.optJSONObject("browseEndpoint")?.optString("browseId")?.takeIf { it.isNotBlank() }?.let {
            return YtCollection(it, false, title, subtitle, thumb)
        }
        return null
    }

    private fun parsePanelVideo(r: JSONObject): Song? {
        val id = r.optString("videoId").ifBlank { return null }
        val byline = runs(r.optJSONObject("longBylineText")).ifEmpty { runs(r.optJSONObject("shortBylineText")) }
        return Song(
            id = id,
            title = text(r.optJSONObject("title")),
            artist = artistFrom(byline),
            thumbnail = upscale(lastThumb(r.optJSONObject("thumbnail"))),
            durationSec = parseDuration(text(r.optJSONObject("lengthText"))),
        )
    }

    private fun artistFrom(runs: List<JSONObject>): String {
        val artistRuns = runs.filter { run ->
            val pageType = run.obj(
                "navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs",
                "browseEndpointContextMusicConfig",
            )?.optString("pageType")
            pageType == "MUSIC_PAGE_TYPE_ARTIST" || pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL"
        }
        if (artistRuns.isNotEmpty()) return artistRuns.joinToString(", ") { it.optString("text") }
        return runs.map { it.optString("text").trim() }
            .firstOrNull { it.isNotBlank() && it != "•" && it !in TYPE_WORDS && !NOISE.matches(it) }
            .orEmpty()
    }

    // --------------------------------------------------------------- json helpers

    private fun JSONObject.obj(vararg path: String): JSONObject? {
        var current: JSONObject? = this
        for (key in path) current = current?.optJSONObject(key)
        return current
    }

    private fun runs(o: JSONObject?): List<JSONObject> {
        val arr = o?.optJSONArray("runs") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun text(o: JSONObject?): String {
        if (o == null) return ""
        o.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        return runs(o).joinToString("") { it.optString("text") }
    }

    private fun lastThumb(o: JSONObject?): String {
        val arr = o?.optJSONArray("thumbnails") ?: return ""
        return arr.optJSONObject(arr.length() - 1)?.optString("url").orEmpty()
    }

    private fun upscale(url: String): String = when {
        url.isBlank() -> url
        url.startsWith("//") -> upscale("https:$url")
        "googleusercontent.com" in url || "ggpht.com" in url -> url.replace(Regex("=w\\d+-h\\d+[^&?]*"), "=w544-h544-l90-rj")
        else -> url
    }

    private fun parseDuration(s: String): Long {
        val parts = s.trim().split(':')
        if (parts.size !in 2..3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return 0
        return parts.fold(0L) { acc, p -> acc * 60 + p.toLong() }
    }

    /** All objects stored under any of [keys], in document order. */
    private fun findAllOf(node: Any?, keys: Set<String>): List<Pair<String, JSONObject>> {
        val out = mutableListOf<Pair<String, JSONObject>>()
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    val it = n.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        val v = n.opt(k)
                        if (k in keys && v is JSONObject) out += k to v else walk(v)
                    }
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(node)
        return out
    }

    private fun findFirstOf(node: Any?, keys: Set<String>): JSONObject? = findAllOf(node, keys).firstOrNull()?.second

    private fun findString(node: Any?, key: String): String {
        when (node) {
            is JSONObject -> {
                node.optString(key).takeIf { it.isNotEmpty() && node.opt(key) is String }?.let { return it }
                val it = node.keys()
                while (it.hasNext()) findString(node.opt(it.next()), key).takeIf { s -> s.isNotEmpty() }?.let { s -> return s }
            }
            is JSONArray -> for (i in 0 until node.length()) findString(node.opt(i), key).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    private fun findContinuation(node: Any?): String? {
        findFirstOf(node, setOf("nextContinuationData"))?.optString("continuation")?.takeIf { it.isNotBlank() }?.let { return it }
        return findFirstOf(node, setOf("continuationCommand"))?.optString("token")?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val ORIGIN = "https://music.youtube.com"
        private const val CLIENT_VERSION = "1.20250915.01.00"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val JSON = "application/json".toMediaType()
        private val TYPE_WORDS = setOf("Song", "Video", "Episode", "Single", "Album", "EP", "Playlist", "Artist", "Podcast")
        private val NOISE = Regex("(?i)^(\\d[\\d.,]*\\s*[KMB]?\\s*(views|plays|subscribers|songs|tracks)|\\d+:\\d{2}(:\\d{2})?|\\d{4})$")
    }
}
