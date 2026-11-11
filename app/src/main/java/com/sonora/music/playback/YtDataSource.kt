package com.sonora.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import com.sonora.music.AppGraph
import com.sonora.music.data.db.DL_DONE
import java.io.File
import java.io.IOException
import kotlin.math.min

/**
 * Resolves yt://song/<id> to the best source, in order:
 *  1. a downloaded file (fully offline)
 *  2. the on-disk stream cache (offline if the song was fully played before)
 *  3. YouTube, fetched in chunked range requests to dodge throttling
 */
class YtDataSource(
    private val local: DataSource,
    private val remote: DataSource,
) : DataSource {

    private var active: DataSource? = null
    private var remoteSpec: DataSpec? = null
    private var position = 0L
    private var bytesRemaining = UNSET
    private var chunkRemaining = 0L
    private var chunked = false

    override fun addTransferListener(transferListener: TransferListener) {
        local.addTransferListener(transferListener)
        remote.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val id = dataSpec.uri.lastPathSegment ?: throw IOException("Invalid song uri ${dataSpec.uri}")

        localFile(id)?.let { file ->
            active = local
            return local.open(dataSpec.buildUpon().setUri(Uri.fromFile(file)).build())
        }

        val cache = AppGraph.playerCache
        val cachedLength = ContentMetadata.getContentLength(cache.getContentMetadata(id))
        val url: String
        val total: Long
        if (cachedLength > 0 && cache.isCached(id, 0, cachedLength)) {
            url = "https://cache.sonora/$id" // never hit: fully served from cache
            total = cachedLength
        } else {
            val audio = try {
                AppGraph.youtube.resolveAudio(id)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("Couldn't resolve stream for $id", e)
            }
            if (audio.contentLength > 0) {
                val mutations = ContentMetadataMutations()
                ContentMetadataMutations.setContentLength(mutations, audio.contentLength)
                cache.applyContentMetadataMutations(id, mutations)
            }
            url = audio.url
            total = audio.contentLength
        }

        active = remote
        position = dataSpec.position
        bytesRemaining = when {
            dataSpec.length != UNSET -> dataSpec.length
            total > 0 -> total - position
            else -> UNSET
        }
        chunked = total > 0
        remoteSpec = dataSpec.buildUpon().setUri(url).setKey(id).build()
        if (bytesRemaining != 0L) openChunk()
        return bytesRemaining
    }

    private fun openChunk() {
        val spec = remoteSpec ?: throw IOException("Not opened")
        val length = when {
            !chunked -> bytesRemaining
            bytesRemaining == UNSET -> CHUNK
            else -> min(CHUNK, bytesRemaining)
        }
        chunkRemaining = if (length == UNSET) Long.MAX_VALUE else length
        try {
            remote.open(spec.buildUpon().setPosition(position).setLength(length).build())
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            // Expired / revoked URL: drop it so the player's retry fetches a fresh one.
            if (e.responseCode == 403 || e.responseCode == 410) spec.key?.let(AppGraph.youtube::invalidate)
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val source = active ?: throw IOException("Not opened")
        if (source === local) return local.read(buffer, offset, length)
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        if (chunkRemaining == 0L) {
            remote.close()
            openChunk()
        }
        val toRead = min(length.toLong(), chunkRemaining).toInt()
        val read = remote.read(buffer, offset, toRead)
        if (read == C.RESULT_END_OF_INPUT) {
            bytesRemaining = 0
            return read
        }
        position += read
        chunkRemaining -= read
        if (bytesRemaining != UNSET) bytesRemaining -= read
        return read
    }

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }

    private fun localFile(id: String): File? =
        AppGraph.db.songDao().getBlocking(id)
            ?.takeIf { it.downloadState == DL_DONE }
            ?.localPath
            ?.let(::File)
            ?.takeIf { it.exists() }

    private companion object {
        const val UNSET = -1L // == C.LENGTH_UNSET
        const val CHUNK = 1L * 1024 * 1024
    }
}
