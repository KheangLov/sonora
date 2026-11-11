package com.sonora.music.ui.player

import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import com.sonora.music.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val ColorCache = LruCache<String, Color>(96)

/** Extracts a vibrant color from the artwork (tiny 96px decode, cached) and animates to it. */
@Composable
fun rememberArtworkColor(url: String?, fallback: Color = Color(0xFF3A3A44)): State<Color> {
    val context = LocalContext.current
    var target by remember { mutableStateOf(url?.let { ColorCache.get(it) } ?: fallback) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) {
            target = fallback
            return@LaunchedEffect
        }
        ColorCache.get(url)?.let {
            target = it
            return@LaunchedEffect
        }
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context).data(url).size(96).allowHardware(false).build()
        )
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@LaunchedEffect
        val color = withContext(Dispatchers.Default) {
            val palette = Palette.from(bitmap).maximumColorCount(16).generate()
            (palette.vibrantSwatch ?: palette.darkVibrantSwatch ?: palette.dominantSwatch)?.rgb?.let { Color(it) }
        } ?: return@LaunchedEffect
        ColorCache.put(url, color)
        target = color
    }
    return animateColorAsState(target, tween(700), label = "artworkColor")
}

/** Polls playback position only while composed: 5 Hz when playing, 1 Hz when paused. */
@Composable
fun rememberPlaybackPosition(isPlaying: Boolean, key: Any?): State<Long> =
    produceState(initialValue = AppGraph.player.position(), isPlaying, key) {
        while (true) {
            value = AppGraph.player.position()
            delay(if (isPlaying) 200 else 1000)
        }
    }

/** Thin custom seek bar that grows while scrubbing. All drawing in the draw phase. */
@Composable
fun SeekBar(
    progress: () -> Float,
    onScrub: (Float?) -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    trackColor: Color = Color.White.copy(alpha = 0.22f),
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val scrub by rememberUpdatedState(onScrub)

    Box(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset -> seek((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = (offset.x / size.width).coerceIn(0f, 1f)
                        scrub(dragging)
                    },
                    onDragEnd = {
                        dragging?.let(seek)
                        dragging = null
                        scrub(null)
                    },
                    onDragCancel = {
                        dragging = null
                        scrub(null)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragging = (change.position.x / size.width).coerceIn(0f, 1f)
                        scrub(dragging)
                    },
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val active = dragging != null
            val p = (dragging ?: progress()).coerceIn(0f, 1f)
            val stroke = (if (active) 6.dp else 4.dp).toPx()
            val y = size.height / 2
            drawLine(trackColor, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
            drawLine(color, Offset(0f, y), Offset(size.width * p, y), stroke, StrokeCap.Round)
            drawCircle(color, radius = (if (active) 9.dp else 6.dp).toPx(), center = Offset(size.width * p, y))
        }
    }
}
