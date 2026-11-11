@file:OptIn(ExperimentalFoundationApi::class)

package com.sonora.music.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sonora.music.AppGraph
import com.sonora.music.playback.PlayerState
import com.sonora.music.ui.components.Artwork

@Composable
fun MiniPlayer(state: PlayerState, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val song = state.current ?: return
    val conn = AppGraph.player
    val accent by rememberArtworkColor(song.thumbnail)
    val position = rememberPlaybackPosition(state.isPlaying, song.id)
    val duration = state.durationMs.takeIf { it > 0 } ?: (song.durationSec * 1000)
    var swipeX by remember { mutableFloatStateOf(0f) }

    Box(
        modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .fillMaxWidth()
            .graphicsLayer { translationX = swipeX * 0.5f }
            .clip(RoundedCornerShape(10.dp))
            .background(lerp(accent, Color.Black, 0.55f))
            .clickable(onClick = onExpand)
            .pointerInput(Unit) {
                val threshold = 80.dp.toPx()
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (swipeX < -threshold) conn.next() else if (swipeX > threshold) conn.previous()
                        swipeX = 0f
                    },
                    onDragCancel = { swipeX = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        swipeX += amount
                    },
                )
            }
            // Progress line drawn in draw phase only — no recomposition per tick.
            .drawBehind {
                val p = if (duration > 0) (position.value.toFloat() / duration).coerceIn(0f, 1f) else 0f
                val h = 2.dp.toPx()
                drawRect(Color.White.copy(alpha = 0.18f), Offset(0f, size.height - h), Size(size.width, h))
                drawRect(Color.White, Offset(0f, size.height - h), Size(size.width * p, h))
            }
    ) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(song.thumbnail, Modifier.size(42.dp), 6.dp, 140)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(),
                )
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
            IconButton(onClick = { conn.togglePlay() }) {
                Crossfade(state.isPlaying, label = "miniPP") { playing ->
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(30.dp))
                }
            }
            IconButton(onClick = { conn.next() }, enabled = state.hasNext) {
                Icon(Icons.Rounded.SkipNext, "Next")
            }
        }
    }
}
