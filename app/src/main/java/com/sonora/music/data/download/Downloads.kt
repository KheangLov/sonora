package com.sonora.music.data.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sonora.music.AppGraph
import com.sonora.music.data.db.DL_DONE
import com.sonora.music.data.db.DL_FAILED
import com.sonora.music.data.db.DL_QUEUED
import com.sonora.music.data.db.DL_RUNNING
import com.sonora.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

object Downloads {
    private const val KEY_ID = "id"

    fun dir(context: Context) = File(context.filesDir, "downloads").apply { mkdirs() }

    fun download(context: Context, song: Song) {
        val appContext = context.applicationContext
        AppGraph.scope.launch {
            AppGraph.library.ensure(song)
            val dao = AppGraph.db.songDao()
            if (dao.get(song.id)?.downloadState == DL_DONE) return@launch
            dao.setDownloadState(song.id, DL_QUEUED, 0)
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_ID to song.id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag("download")
                .build()
            WorkManager.getInstance(appContext)
                .enqueueUniqueWork("download_${song.id}", ExistingWorkPolicy.KEEP, request)
        }
    }

    fun downloadAll(context: Context, songs: List<Song>) = songs.forEach { download(context, it) }

    fun remove(context: Context, id: String) {
        val appContext = context.applicationContext
        WorkManager.getInstance(appContext).cancelUniqueWork("download_$id")
        AppGraph.scope.launch {
            val dao = AppGraph.db.songDao()
            dao.get(id)?.localPath?.let { File(it).delete() }
            File(dir(appContext), "$id.part").delete()
            dao.clearDownload(id)
        }
    }

    class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            val id = inputData.getString(KEY_ID) ?: return@withContext Result.failure()
            val dao = AppGraph.db.songDao()
            try {
                dao.setDownloadState(id, DL_RUNNING, 0)
                val audio = AppGraph.youtube.resolveAudio(id, forceFresh = runAttemptCount > 0)
                val dir = dir(applicationContext)
                val part = File(dir, "$id.part")
                val out = File(dir, "$id.${audio.suffix}")

                var total = audio.contentLength
                var pos = if (part.exists()) part.length() else 0L
                var lastProgress = -1

                FileOutputStream(part, true).use { fos ->
                    // Chunked range requests: YouTube throttles single long-lived downloads.
                    while (total < 0 || pos < total) {
                        if (isStopped) return@withContext Result.failure()
                        val end = if (total > 0) minOf(pos + CHUNK, total) - 1 else pos + CHUNK - 1
                        val req = Request.Builder().url(audio.url).header("Range", "bytes=$pos-$end").build()
                        AppGraph.okHttp.newCall(req).execute().use { resp ->
                            when {
                                resp.code == 416 -> total = pos
                                !resp.isSuccessful -> {
                                    if (resp.code == 403) AppGraph.youtube.invalidate(id)
                                    throw IOException("HTTP ${resp.code}")
                                }
                                else -> {
                                    if (resp.code == 200 && pos > 0) {
                                        // Server ignored Range: restart cleanly.
                                        fos.channel.truncate(0)
                                        pos = 0
                                    }
                                    if (total < 0) {
                                        total = resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                                    }
                                    val written = resp.body?.byteStream()?.copyTo(fos) ?: 0L
                                    pos += written
                                    if (written == 0L || resp.code == 200) total = pos
                                }
                            }
                            Unit
                        }
                        if (total > 0) {
                            val progress = (pos * 100 / total).toInt()
                            if (progress != lastProgress) {
                                lastProgress = progress
                                dao.setDownloadState(id, DL_RUNNING, progress)
                            }
                        }
                    }
                }

                if (!part.renameTo(out)) throw IOException("Couldn't finalize file")
                dao.setDownloaded(id, out.absolutePath, System.currentTimeMillis())
                Result.success()
            } catch (e: Exception) {
                if (runAttemptCount < 3) {
                    dao.setDownloadState(id, DL_QUEUED, 0)
                    Result.retry()
                } else {
                    dao.setDownloadState(id, DL_FAILED, 0)
                    Result.failure()
                }
            }
        }

        private companion object {
            const val CHUNK = 2L * 1024 * 1024
        }
    }
}
