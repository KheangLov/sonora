package com.sonora.music

import android.app.Application
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.room.Room
import com.sonora.music.data.LibraryRepository
import com.sonora.music.data.filter.ContentFilter
import com.sonora.music.data.lyrics.LyricsRepository
import com.sonora.music.data.account.AccountStore
import com.sonora.music.data.youtube.YtMusicApi
import com.sonora.music.data.db.AppDatabase
import com.sonora.music.data.youtube.NewPipeDownloader
import com.sonora.music.data.youtube.StreamHeadersInterceptor
import com.sonora.music.data.youtube.YouTubeRepository
import com.sonora.music.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import java.io.File
import java.util.concurrent.TimeUnit

/** Tiny manual service locator — no DI framework, zero startup cost. */
object AppGraph {
    lateinit var app: Application
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(StreamHeadersInterceptor())
            .build()
    }

    val db: AppDatabase by lazy {
        Room.databaseBuilder(app, AppDatabase::class.java, "sonora.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()
    }

    val youtube: YouTubeRepository by lazy { YouTubeRepository() }
    val account: AccountStore by lazy { AccountStore(app) }
    val ytMusic: YtMusicApi by lazy { YtMusicApi(okHttp, account) }
    val library: LibraryRepository by lazy { LibraryRepository(db) }
    val lyrics: LyricsRepository by lazy { LyricsRepository(okHttp, db.lyricsDao()) }

    /** Streamed audio is cached on disk, so replays are instant and work offline. */
    val playerCache: SimpleCache by lazy {
        SimpleCache(
            File(app.cacheDir, "audio"),
            LeastRecentlyUsedCacheEvictor(768L * 1024 * 1024),
            StandaloneDatabaseProvider(app),
        )
    }

    val player: PlayerConnection by lazy { PlayerConnection(app) }

    fun init(application: Application) {
        app = application
        NewPipe.init(NewPipeDownloader(okHttp))
        ContentFilter.start(scope, db.songDao().observeDislikedIds())
    }
}
