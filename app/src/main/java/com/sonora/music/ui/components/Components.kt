@file:OptIn(ExperimentalFoundationApi::class)

package com.sonora.music.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sonora.music.data.model.Song

/** Artwork with a requested decode size — keeps memory + jank low in long lists. */
@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 6.dp,
    sizePx: Int = 256,
) {
    val context = LocalContext.current
    val request = remember(url, sizePx) {
        ImageRequest.Builder(context).data(url).size(sizePx).crossfade(220).build()
    }
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Animated equalizer bars for the currently playing row. Draw-phase only: no recomposition. */
@Composable
fun PlayingBars(
    playing: Boolean,
    modifier: Modifier = Modifier.size(18.dp),
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val transition = rememberInfiniteTransition(label = "bars")
    val bars = listOf(0, 160, 320).map { delay ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(520, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(delay),
            ),
            label = "bar$delay",
        )
    }
    Canvas(modifier) {
        val w = size.width / 5f
        bars.forEachIndexed { i, anim ->
            val h = size.height * (if (playing) anim.value else 0.3f)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * 2 * w, size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2),
            )
        }
    }
}

@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    downloaded: Boolean = false,
    status: String? = null,
    onMore: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onMore)
            .padding(start = 16.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp)) {
            Artwork(song.thumbnail, Modifier.fillMaxSize(), 6.dp, 160)
            if (isCurrent) {
                Box(
                    Modifier.matchParentSize().clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) { PlayingBars(isPlaying) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (downloaded) {
                    Icon(
                        Icons.Rounded.DownloadForOffline,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                val sub = status ?: listOfNotNull(
                    song.artist.ifBlank { null },
                    song.durationText.takeIf { song.durationSec > 0 },
                ).joinToString(" • ")
                Text(
                    sub,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            trailing != null -> trailing()
            onMore != null -> IconButton(onClick = onMore) {
                Icon(Icons.Rounded.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
fun SongCard(
    song: Song,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 148.dp,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
) {
    Column(
        modifier
            .width(width)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box {
            Artwork(song.thumbnail, Modifier.size(width), 10.dp, 420)
            if (isCurrent) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).size(30.dp)
                        .clip(RoundedCornerShape(15.dp)).background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) { PlayingBars(isPlaying, Modifier.size(14.dp)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            song.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            song.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 10.dp)) {
        if (subtitle != null) {
            Text(
                subtitle.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun Modifier.shimmer(corner: Dp = 6.dp): Modifier {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "alpha",
    )
    return this
        .graphicsLayer { alpha = pulse }
        .clip(RoundedCornerShape(corner))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
}

@Composable
fun SkeletonRow() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).shimmer())
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.7f).height(14.dp).shimmer(4.dp))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(0.4f).height(12.dp).shimmer(4.dp))
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector = Icons.Rounded.CloudOff,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), content = actions)
    }
}
