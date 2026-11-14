@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.sonora.music.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.sonora.music.AppGraph
import com.sonora.music.data.db.DL_DONE
import com.sonora.music.data.db.DL_QUEUED
import com.sonora.music.data.db.DL_RUNNING
import com.sonora.music.data.download.Downloads
import com.sonora.music.data.model.formatDuration
import com.sonora.music.playback.PlayerState
import com.sonora.music.playback.fx.AudioFx
import com.sonora.music.ui.components.Artwork
import com.sonora.music.ui.components.SongActionsSheet
import com.sonora.music.ui.components.SongRow
import com.sonora.music.ui.components.shareSong
import com.sonora.music.ui.components.toast
import com.sonora.music.ui.theme.Background
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun NowPlayingScreen(onCollapse: () -> Unit, onOpenEqualizer: () -> Unit = {}) {
    val context = LocalContext.current
    val conn = AppGraph.player
    val state by conn.state.collectAsStateWithLifecycle()
    val sleepAt by conn.sleepAt.collectAsStateWithLifecycle()
    val fx by AudioFx.settings.collectAsStateWithLifecycle()
    val song = state.current ?: return

    val entity by remember(song.id) { AppGraph.db.songDao().observe(song.id) }
        .collectAsStateWithLifecycle(initialValue = null)
    val accent by rememberArtworkColor(song.thumbnail)
    val position = rememberPlaybackPosition(state.isPlaying, song.id)
    val duration = state.durationMs.takeIf { it > 0 } ?: (song.durationSec * 1000)
    var scrub by remember { mutableStateOf<Float?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }

    // Drag down anywhere to dismiss
    val scope = rememberCoroutineScope()
    val dragY = remember { Animatable(0f) }
    val dismissPx = with(LocalDensity.current) { 160.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragY.value.roundToInt()) }
            .background(
                Brush.verticalGradient(
                    listOf(lerp(accent, Color.Black, 0.25f), lerp(accent, Color.Black, 0.72f), Background)
                )
            )
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (dragY.value > dismissPx) onCollapse() else dragY.animateTo(0f, spring())
                        }
                    },
                    onDragCancel = { scope.launch { dragY.animateTo(0f) } },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        scope.launch { dragY.snapTo((dragY.value + amount).coerceAtLeast(0f)) }
                    },
                )
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCollapse, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Collapse", Modifier.size(32.dp))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "PLAYING FROM YOUR QUEUE",
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.5.sp,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Text(
                        "${state.index + 1} of ${state.queue.size}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                IconButton(onClick = { showMenu = true }, modifier = Modifier.offset(x = 12.dp)) {
                    Icon(Icons.Rounded.MoreVert, "More")
                }
            }

            Spacer(Modifier.weight(1f))

            // Artwork: breathes with play/pause, swipe to change track
            val artScale by animateFloatAsState(
                if (state.isPlaying) 1f else 0.88f,
                spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
                label = "artScale",
            )
            var swipeX by remember { mutableFloatStateOf(0f) }
            AnimatedContent(
                targetState = song,
                contentKey = { it.id },
                transitionSpec = {
                    (fadeIn(tween(320)) + scaleIn(tween(320), initialScale = 0.92f)) togetherWith fadeOut(tween(180))
                },
                label = "artwork",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .pointerInput(Unit) {
                        val threshold = 96.dp.toPx()
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (swipeX > threshold) conn.previous() else if (swipeX < -threshold) conn.next()
                                swipeX = 0f
                            },
                            onDragCancel = { swipeX = 0f },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                swipeX += amount
                            },
                        )
                    }
                    .graphicsLayer {
                        scaleX = artScale
                        scaleY = artScale
                        translationX = swipeX * 0.35f
                        rotationZ = swipeX / 90f
                    },
            ) { s ->
                Artwork(
                    s.thumbnail,
                    Modifier.fillMaxSize().shadow(32.dp, RoundedCornerShape(14.dp)),
                    corner = 14.dp,
                    sizePx = 1080,
                )
            }

            Spacer(Modifier.weight(1f))

            // Title + like
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                }
                val liked = entity?.liked == true
                val likeScale by animateFloatAsState(
                    if (liked) 1f else 0.9f,
                    spring(dampingRatio = Spring.DampingRatioHighBouncy),
                    label = "like",
                )
                IconButton(onClick = { AppGraph.scope.launch { AppGraph.library.setLiked(song, !liked) } }) {
                    Icon(
                        if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (liked) "Unlike" else "Like",
                        tint = if (liked) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.size(28.dp).graphicsLayer { scaleX = likeScale; scaleY = likeScale },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Seek bar + times
            SeekBar(
                progress = { if (duration > 0) position.value.toFloat() / duration else 0f },
                onScrub = { scrub = it },
                onSeek = { conn.seekTo((it * duration).toLong()) },
            )
            Row(Modifier.fillMaxWidth()) {
                val shown = scrub?.let { (it * duration).toLong() } ?: position.value
                Text(formatDuration(shown), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.weight(1f))
                Text(formatDuration(duration), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
            }

            Spacer(Modifier.height(12.dp))

            // Transport controls
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToggleIcon(Icons.Rounded.Shuffle, "Shuffle", state.shuffle) { conn.toggleShuffle() }
                IconButton(onClick = { conn.previous() }, modifier = Modifier.size(60.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(40.dp))
                }
                PlayPauseButton(state.isPlaying, state.isBuffering, accent) { conn.togglePlay() }
                IconButton(onClick = { conn.next() }, modifier = Modifier.size(60.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(40.dp))
                }
                ToggleIcon(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    "Repeat",
                    state.repeatMode != Player.REPEAT_MODE_OFF,
                ) { conn.cycleRepeat() }
            }

            Spacer(Modifier.height(16.dp))

            // Secondary actions
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (entity?.downloadState) {
                    DL_DONE -> IconButton(onClick = {
                        Downloads.remove(context, song.id)
                        context.toast("Removed download")
                    }) { Icon(Icons.Rounded.DownloadDone, "Downloaded", tint = MaterialTheme.colorScheme.primary) }
                    DL_QUEUED, DL_RUNNING -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        val p = (entity?.downloadProgress ?: 0) / 100f
                        CircularProgressIndicator(
                            progress = { p },
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.5.dp,
                            trackColor = Color.White.copy(alpha = 0.2f),
                        )
                    }
                    else -> IconButton(onClick = {
                        Downloads.download(context, song)
                        context.toast("Downloading for offline")
                    }) { Icon(Icons.Rounded.Download, "Download") }
                }
                IconButton(onClick = {
                    conn.dislike(song)
                    context.toast("Disliked — won't play again. Undo in Library")
                }) { Icon(Icons.Rounded.ThumbDownOffAlt, "Dislike") }
                IconButton(onClick = { showLyrics = true }) {
                    Icon(Icons.Rounded.Lyrics, "Lyrics", tint = if (showLyrics) MaterialTheme.colorScheme.primary else Color.White)
                }
                IconButton(onClick = { showSleep = true }) {
                    Icon(
                        Icons.Rounded.Bedtime,
                        "Sleep timer",
                        tint = if (sleepAt != null) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
                IconButton(onClick = onOpenEqualizer) {
                    Icon(
                        Icons.Rounded.GraphicEq,
                        "Equalizer",
                        tint = if (AudioFx.isActive(fx)) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
                IconButton(onClick = { context.shareSong(song) }) { Icon(Icons.Rounded.Share, "Share") }
                IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue") }
            }
        }
    }

    if (showQueue) QueueSheet(state, onDismiss = { showQueue = false })
    if (showMenu) SongActionsSheet(song, onDismiss = { showMenu = false })
    if (showLyrics) LyricsSheet(song, duration, state.isPlaying, accent, onDismiss = { showLyrics = false })
    if (showSleep) SleepTimerDialog(sleepAt, onDismiss = { showSleep = false })
}

@Composable
private fun ToggleIcon(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                label,
                tint = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f),
            )
            Box(
                Modifier.padding(top = 2.dp).size(4.dp).clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
            )
        }
    }
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, buffering: Boolean, accent: Color, onClick: () -> Unit) {
    val pressScale by animateFloatAsState(if (isPlaying) 1f else 1.04f, label = "pp")
    Box(
        Modifier
            .size(76.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(CircleShape)
            .background(Color.White)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(isPlaying, label = "ppIcon") { playing ->
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (playing) "Pause" else "Play",
                tint = Color.Black,
                modifier = Modifier.size(40.dp),
            )
        }
        if (buffering) {
            CircularProgressIndicator(
                modifier = Modifier.size(76.dp),
                color = lerp(accent, Color.Black, 0.2f),
                strokeWidth = 3.dp,
            )
        }
    }
}

@Composable
private fun QueueSheet(state: PlayerState, onDismiss: () -> Unit) {
    val conn = AppGraph.player
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.index.coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Text(
            "Up next",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxHeight(0.75f)) {
            itemsIndexed(state.queue, key = { i, s -> "q_${i}_${s.id}" }) { i, s ->
                SongRow(
                    s,
                    onClick = { conn.skipTo(i) },
                    isCurrent = i == state.index,
                    isPlaying = state.isPlaying,
                    trailing = if (i != state.index) {
                        {
                            IconButton(onClick = { conn.removeAt(i) }) {
                                Icon(Icons.Rounded.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else null,
                )
            }
            item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
        }
    }
}

@Composable
private fun SleepTimerDialog(sleepAt: Long?, onDismiss: () -> Unit) {
    val conn = AppGraph.player
    val options = listOf(5, 15, 30, 45, 60, 90)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Sleep timer") },
        text = {
            Column {
                if (sleepAt != null) {
                    val mins = ((sleepAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0) + 1
                    Text(
                        "Music stops in about $mins min",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                options.forEach { m ->
                    Text(
                        "$m minutes",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { conn.setSleepTimer(m); onDismiss() }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (sleepAt != null) TextButton(onClick = { conn.setSleepTimer(0); onDismiss() }) { Text("Turn off") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
