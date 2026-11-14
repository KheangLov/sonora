package com.sonora.music.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.sonora.music.AppGraph
import com.sonora.music.R
import com.sonora.music.playback.fx.AudioFx
import com.sonora.music.playback.fx.FxAudioProcessor
import com.sonora.music.ui.MainActivity
import com.sonora.music.widget.WidgetUpdater
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Background playback:
 *  - lock screen / notification / Bluetooth / Android Auto controls (MediaSession)
 *  - ♥ Like button right in the notification
 *  - queue + position saved continuously and restored on next launch
 *  - headset / Bluetooth "play" resumes the last session even after the app was killed
 *  - keeps the home-screen widget in sync
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val scope = MainScope()
    private var likeJob: Job? = null
    private val likeCommand = SessionCommand(ACTION_LIKE, Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()

        val remote = CacheDataSource.Factory()
            .setCache(AppGraph.playerCache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(AppGraph.okHttp))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val dataSourceFactory = DataSource.Factory {
            YtDataSource(FileDataSource(), remote.createDataSource())
        }

        // Equalizer + effects run inside ExoPlayer's audio pipeline (see FxAudioProcessor).
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(FxAudioProcessor()))
                .build()
        }

        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true, // pauses for calls, ducks for navigation prompts
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones unplug
            .setWakeMode(C.WAKE_MODE_NETWORK)  // keep streaming with the screen off
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 120_000, 1_000, 2_000)
                    .build()
            )
            .build()

        // Restore the last session (paused) so the app opens right where you left off.
        QueueStore.load(this)?.let { saved ->
            player.setMediaItems(saved.songs.map { it.toMediaItem() }, saved.index, saved.positionMs)
        }

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val song = mediaItem?.toSong() ?: return
                AppGraph.scope.launch { AppGraph.library.markPlayed(song) }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_IS_PLAYING_CHANGED,
                    )
                ) {
                    QueueStore.save(this@PlaybackService, player)
                }
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED,
                    )
                ) {
                    refreshWidget(player)
                }
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED)) {
                    observeLike(player.currentMediaItem?.mediaId)
                }
            }
        })

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCustomLayout(listOf(likeButton(false)))
            .setCallback(SessionCallback())
            .build()

        observeLike(player.currentMediaItem?.mediaId)
        refreshWidget(player)

        // Tempo from the equalizer screen (pitch is preserved).
        scope.launch {
            AudioFx.settings.map { it.tempo }.distinctUntilChanged().collect { tempo ->
                player.playbackParameters = PlaybackParameters(tempo)
            }
        }
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(likeCommand)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_LIKE) {
                val song = session.player.currentMediaItem?.toSong()
                if (song != null) {
                    AppGraph.scope.launch {
                        val liked = AppGraph.db.songDao().get(song.id)?.liked == true
                        AppGraph.library.setLiked(song, !liked)
                    }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { it.withPlaybackUri() }.toMutableList())

        /** Bluetooth / headset play button after the app was killed → continue last session. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = QueueStore.load(this@PlaybackService)
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    saved.songs.map { it.toMediaItem() },
                    saved.index,
                    saved.positionMs,
                )
            )
        }
    }

    private fun likeButton(liked: Boolean): CommandButton =
        CommandButton.Builder()
            .setDisplayName(if (liked) "Remove from Liked" else "Like")
            .setIconResId(if (liked) R.drawable.ic_notif_heart_filled else R.drawable.ic_notif_heart)
            .setSessionCommand(likeCommand)
            .build()

    /** Keeps the notification's ♥ in sync with the library, wherever the like came from. */
    private fun observeLike(id: String?) {
        likeJob?.cancel()
        if (id == null) return
        likeJob = scope.launch {
            AppGraph.db.songDao().observe(id)
                .map { it?.liked == true }
                .distinctUntilChanged()
                .collect { liked ->
                    session?.setCustomLayout(listOf(likeButton(liked)))
                    session?.player?.let(::refreshWidget)
                }
        }
    }

    private fun refreshWidget(player: Player) {
        val song = player.currentMediaItem?.toSong()
        val playing = player.isPlaying
        val shuffle = player.shuffleModeEnabled
        val repeat = when (player.repeatMode) {
            Player.REPEAT_MODE_ONE -> 1
            Player.REPEAT_MODE_ALL -> 2
            else -> 0
        }
        val context = applicationContext
        AppGraph.scope.launch {
            runCatching {
                val liked = song?.let { AppGraph.db.songDao().get(it.id)?.liked } == true
                WidgetUpdater.update(context, song, playing, WidgetUpdater.Extras(liked, shuffle, repeat))
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        player?.let { QueueStore.save(this, it) }
        // Keep playing after the app is swiped away; stop only if nothing is playing.
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            QueueStore.save(this@PlaybackService, player)
            refreshWidget(player.also { it.pause() })
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_LIKE = "sonora.LIKE"
    }
}
