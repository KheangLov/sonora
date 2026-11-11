package com.sonora.music.playback

import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.sonora.music.AppGraph
import com.sonora.music.data.model.Song
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class PlayerState(
    val current: Song? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<Song> = emptyList(),
    val index: Int = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
)

/** UI-facing wrapper around a MediaController bound to [PlaybackService]. Main thread only. */
class PlayerConnection(private val context: Context) {

    private val scope = MainScope()
    private var controller: MediaController? = null
    private var connecting = false
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _sleepAt = MutableStateFlow<Long?>(null)
    val sleepAt: StateFlow<Long?> = _sleepAt.asStateFlow()
    private var sleepJob: Job? = null

    private var cachedQueue: List<Song> = emptyList()
    private var errorStreak = 0
    private var extending = false

    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            sync(rebuildQueue = true)
            while (pending.isNotEmpty()) pending.removeFirst().invoke(c)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else {
            pending.addLast(block)
            connect()
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            sync(rebuildQueue = events.contains(Player.EVENT_TIMELINE_CHANGED))
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            errorStreak = 0
            maybeExtendQueue()
        }

        override fun onPlayerError(error: PlaybackException) {
            val c = controller ?: return
            errorStreak++
            if (errorStreak == 1 && !isOffline()) {
                // Usually an expired stream URL — one silent retry fetches a fresh one.
                c.prepare()
                c.play()
                return
            }
            _messages.tryEmit(
                if (isOffline()) "You're offline — downloaded songs still play"
                else "Couldn't play this song, skipping"
            )
            if (c.hasNextMediaItem() && errorStreak < 6) {
                c.seekToNextMediaItem()
                c.prepare()
                c.play()
            }
        }
    }

    private fun sync(rebuildQueue: Boolean) {
        val c = controller ?: return
        if (rebuildQueue || cachedQueue.size != c.mediaItemCount) {
            cachedQueue = List(c.mediaItemCount) { c.getMediaItemAt(it).toSong() }
        }
        _state.value = PlayerState(
            current = c.currentMediaItem?.toSong(),
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = c.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it },
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            queue = cachedQueue,
            index = c.currentMediaItemIndex,
            hasNext = c.hasNextMediaItem(),
            hasPrevious = c.hasPreviousMediaItem(),
        )
    }

    // ------------------------------------------------------------ commands

    fun play(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false) = withController { c ->
        if (songs.isEmpty()) return@withController
        c.shuffleModeEnabled = shuffle
        val start = if (shuffle) songs.indices.random() else startIndex.coerceIn(songs.indices)
        c.setMediaItems(songs.map { it.toMediaItem() }, start, 0L)
        c.prepare()
        c.play()
    }

    /** Play one song, then keep going with related tracks (Spotify-style autoplay). */
    fun playRadio(song: Song) {
        play(listOf(song))
        scope.launch {
            val related = fetchRelated(song.id)
            withController { c ->
                if (c.currentMediaItem?.mediaId == song.id) appendUnique(c, related)
            }
        }
    }

    fun startRadioFrom(song: Song) = playRadio(song)

    fun playNext(song: Song) = withController { c ->
        if (c.mediaItemCount == 0) play(listOf(song))
        else c.addMediaItem(c.currentMediaItemIndex + 1, song.toMediaItem())
    }

    fun addToQueue(song: Song) = withController { c ->
        if (c.mediaItemCount == 0) play(listOf(song)) else c.addMediaItem(song.toMediaItem())
    }

    fun togglePlay() = withController { c ->
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }
    fun skipTo(index: Int) = withController {
        it.seekToDefaultPosition(index)
        it.play()
    }
    fun removeAt(index: Int) = withController { it.removeMediaItem(index) }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun position(): Long = controller?.currentPosition ?: 0L

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _sleepAt.value = null
            return
        }
        val delayMs = minutes * 60_000L
        _sleepAt.value = System.currentTimeMillis() + delayMs
        sleepJob = scope.launch {
            delay(delayMs)
            controller?.pause()
            _sleepAt.value = null
        }
    }

    // ------------------------------------------------------------ autoplay

    private suspend fun fetchRelated(id: String): List<Song> =
        runCatching { AppGraph.youtube.related(id) }.getOrDefault(emptyList())

    private fun appendUnique(c: MediaController, songs: List<Song>) {
        val existing = HashSet<String>()
        for (i in 0 until c.mediaItemCount) existing += c.getMediaItemAt(i).mediaId
        val toAdd = songs.filter { existing.add(it.id) }
        if (toAdd.isNotEmpty()) c.addMediaItems(toAdd.map { it.toMediaItem() })
    }

    private fun maybeExtendQueue() {
        val c = controller ?: return
        if (extending || c.repeatMode != Player.REPEAT_MODE_OFF) return
        if (c.mediaItemCount - c.currentMediaItemIndex > 3) return
        val id = c.currentMediaItem?.mediaId ?: return
        extending = true
        scope.launch {
            val related = fetchRelated(id)
            controller?.let { appendUnique(it, related) }
            extending = false
        }
    }

    private fun isOffline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
