@file:OptIn(ExperimentalMaterial3Api::class)

package com.sonora.music.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonora.music.AppGraph
import com.sonora.music.data.lyrics.Lyrics
import com.sonora.music.data.model.Song
import com.sonora.music.ui.components.EmptyState

private sealed interface LyricsUi {
    data object Loading : LyricsUi
    data object NotFound : LyricsUi
    data class Found(val lyrics: Lyrics) : LyricsUi
}

/** Full-height lyrics. Synced lyrics follow the song, highlight the current line, and seek on tap. */
@Composable
fun LyricsSheet(
    song: Song,
    durationMs: Long,
    isPlaying: Boolean,
    accent: Color,
    onDismiss: () -> Unit,
) {
    var attempt by remember(song.id) { mutableIntStateOf(0) }
    val ui by produceState<LyricsUi>(LyricsUi.Loading, song.id, attempt) {
        value = LyricsUi.Loading
        val result = runCatching { AppGraph.lyrics.get(song, durationMs / 1000, forceRefresh = attempt > 0) }.getOrNull()
        value = result?.let { LyricsUi.Found(it) } ?: LyricsUi.NotFound
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val height = LocalConfiguration.current.screenHeightDp.dp * 0.88f

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = lerp(accent, Color.Black, 0.55f),
        contentColor = Color.White,
    ) {
        Column(Modifier.fillMaxWidth().height(height)) {
            Row(Modifier.padding(start = 24.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lyrics", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${song.title} • ${song.artist}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
                if (ui !is LyricsUi.Loading) {
                    IconButton(onClick = { attempt++ }) { Icon(Icons.Rounded.Refresh, "Search again") }
                }
            }
            when (val state = ui) {
                LyricsUi.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                }
                LyricsUi.NotFound -> EmptyState(
                    icon = Icons.Rounded.Lyrics,
                    title = "No lyrics found",
                    message = "We couldn't find lyrics for this song. Instrumentals and very new releases often don't have any yet.",
                ) {
                    OutlinedButton(onClick = { attempt++ }) { Text("Try again") }
                }
                is LyricsUi.Found ->
                    if (state.lyrics.isSynced) SyncedLyrics(state.lyrics, song.id, isPlaying, accent)
                    else PlainLyrics(state.lyrics)
            }
        }
    }
}

@Composable
private fun SyncedLyrics(lyrics: Lyrics, songId: String, isPlaying: Boolean, accent: Color) {
    val conn = AppGraph.player
    val position by rememberPlaybackPosition(isPlaying, songId)
    val lines = lyrics.lines
    // Index of the line being sung right now (-1 before the first line).
    val current by remember(lines) {
        derivedStateOf { lines.indexOfLast { it.timeMs <= position + 250 } }
    }
    val listState = rememberLazyListState()
    var userScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) userScrolling = true
        else { kotlinx.coroutines.delay(2500); userScrolling = false }
    }
    // Keep the current line a few lines down from the top, so upcoming lines stay visible.
    LaunchedEffect(current, userScrolling) {
        if (!userScrolling && current >= 0) {
            listState.animateScrollToItem((current - 3).coerceAtLeast(0))
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 320.dp),
    ) {
        itemsIndexed(lines, key = { i, l -> "${i}_${l.timeMs}" }) { i, line ->
            val active = i == current
            val passed = i < current
            val color by animateColorAsState(
                when {
                    active -> Color.White
                    passed -> Color.White.copy(alpha = 0.55f)
                    else -> Color.White.copy(alpha = 0.35f)
                },
                tween(300),
                label = "lyricColor",
            )
            val scale by animateFloatAsState(if (active) 1f else 0.94f, tween(300), label = "lyricScale")
            Text(
                line.text.ifBlank { "♪" },
                color = color,
                fontSize = 26.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { conn.seekTo(line.timeMs) }
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                    }
                    .padding(vertical = 8.dp),
            )
        }
        item(key = "source") { Source(lyrics.source) }
    }
}

@Composable
private fun PlainLyrics(lyrics: Lyrics) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            lyrics.plain.orEmpty(),
            fontSize = 20.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.92f),
        )
        Source(lyrics.source, note = "Not time-synced")
        Spacer(Modifier.navigationBarsPadding().height(24.dp))
    }
}

@Composable
private fun Source(source: String, note: String? = null) {
    Text(
        listOfNotNull("Lyrics from $source", note).joinToString(" • "),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.5f),
        modifier = Modifier.padding(top = 24.dp),
    )
}
