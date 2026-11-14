package com.sonora.music.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonora.music.AppGraph
import com.sonora.music.data.db.DL_FAILED
import com.sonora.music.data.db.DL_QUEUED
import com.sonora.music.data.db.DL_RUNNING
import com.sonora.music.data.db.SongEntity
import com.sonora.music.data.db.isDownloaded
import com.sonora.music.data.db.toSong
import com.sonora.music.data.download.Downloads
import com.sonora.music.data.model.Song
import com.sonora.music.data.model.formatDuration
import com.sonora.music.ui.components.Artwork
import com.sonora.music.ui.components.EmptyState
import com.sonora.music.ui.components.SongActionsSheet
import com.sonora.music.ui.components.SongRow
import com.sonora.music.ui.components.toast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** One screen for Liked / Downloads / History / any playlist. */
@Composable
fun CollectionScreen(
    kind: String,
    playlistId: Long,
    bottomPadding: Dp,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val dao = AppGraph.db.songDao()
    val playlistDao = AppGraph.db.playlistDao()
    val conn = AppGraph.player

    val songsFlow: Flow<List<SongEntity>> = remember(kind, playlistId) {
        when (kind) {
            "liked" -> dao.observeLiked()
            "downloads" -> dao.observeDownloads()
            "history" -> dao.observeHistory(300)
            "disliked" -> dao.observeDisliked()
            else -> playlistDao.observeSongs(playlistId)
        }
    }
    val entities by songsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlist by remember(playlistId) {
        if (kind == "playlist") playlistDao.observe(playlistId) else flowOf(null)
    }.collectAsStateWithLifecycle(initialValue = null)
    val player by conn.state.collectAsStateWithLifecycle()
    var menuSong by remember { mutableStateOf<Song?>(null) }

    val songs = remember(entities) { entities.map { it.toSong() } }
    // Only finished downloads are playable in the Downloads list
    val playable = remember(entities, kind) {
        when (kind) {
            "downloads" -> entities.filter { it.isDownloaded && !it.disliked }.map { it.toSong() }
            "disliked" -> emptyList()
            else -> songs
        }
    }
    var confirmRestoreAll by remember { mutableStateOf(false) }

    val (title, icon, gradient) = when (kind) {
        "liked" -> Triple("Liked Songs", Icons.Rounded.Favorite, LikedGradient)
        "downloads" -> Triple("Downloads", Icons.Rounded.DownloadForOffline, DownloadsGradient)
        "history" -> Triple("Recently played", Icons.Rounded.History, HistoryGradient)
        "disliked" -> Triple("Disliked songs", Icons.Rounded.ThumbDown, DislikedGradient)
        else -> Triple(playlist?.name ?: "Playlist", Icons.Rounded.LibraryMusic, listOf(Color(0xFF2B2B33), Color(0xFF4A4A57)))
    }
    val totalMs = songs.sumOf { it.durationSec } * 1000

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
        item(key = "header") {
            CollectionHeader(
                title = title,
                subtitle = buildString {
                    append("${songs.size} songs")
                    if (totalMs > 0) append(" • ${formatDuration(totalMs)}")
                },
                icon = icon,
                cover = if (kind == "playlist") songs.firstOrNull()?.thumbnail else null,
                gradient = gradient,
                onBack = onBack,
                onPlay = { conn.play(playable) },
                onShuffle = { conn.play(playable, shuffle = true) },
                enabled = playable.isNotEmpty(),
                extraActions = {
                    when (kind) {
                        "history" -> IconButton(onClick = { AppGraph.scope.launch { dao.clearHistory() } }) {
                            Icon(Icons.Rounded.DeleteSweep, "Clear history")
                        }
                        "downloads" -> Unit
                        "disliked" -> IconButton(onClick = { confirmRestoreAll = true }, enabled = songs.isNotEmpty()) {
                            Icon(Icons.Rounded.SettingsBackupRestore, "Restore all")
                        }
                        else -> IconButton(
                            onClick = {
                                Downloads.downloadAll(context, songs)
                                context.toast("Downloading ${songs.size} songs")
                            },
                            enabled = songs.isNotEmpty(),
                        ) { Icon(Icons.Rounded.Download, "Download all") }
                    }
                },
            )
        }

        if (entities.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = icon,
                    title = when (kind) {
                        "liked" -> "Songs you like appear here"
                        "downloads" -> "No downloads yet"
                        "history" -> "Nothing played yet"
                        "disliked" -> "No disliked songs"
                        else -> "This playlist is empty"
                    },
                    message = when (kind) {
                        "downloads" -> "Tap the download icon on any song to keep it for offline listening."
                        "disliked" -> "Songs you dislike are hidden everywhere and listed here, so you can bring them back."
                        else -> "Long-press any song for more options."
                    },
                )
            }
        }

        itemsIndexed(entities, key = { _, e -> e.id }) { i, e ->
            val song = songs.getOrNull(i)?.takeIf { it.id == e.id } ?: e.toSong()
            val status = when (e.downloadState) {
                DL_QUEUED -> "Waiting to download…"
                DL_RUNNING -> "Downloading ${e.downloadProgress}%"
                DL_FAILED -> if (kind == "downloads") "Download failed — tap ⋮ to retry" else null
                else -> null
            }
            if (kind == "disliked") {
                SongRow(
                    song,
                    onClick = { context.toast("Tap Undo to bring this song back") },
                    trailing = {
                        TextButton(onClick = {
                            conn.undislike(song)
                            context.toast("Song is back")
                        }) { Text("Undo") }
                    },
                )
            } else SongRow(
                song,
                onClick = {
                    val index = playable.indexOfFirst { it.id == e.id }
                    if (index >= 0) conn.play(playable, index) else conn.playRadio(song)
                },
                isCurrent = player.current?.id == e.id,
                isPlaying = player.isPlaying,
                downloaded = e.isDownloaded,
                status = status,
                onMore = { menuSong = song },
            )
        }
    }

    if (confirmRestoreAll) {
        AlertDialog(
            onDismissRequest = { confirmRestoreAll = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Restore all ${songs.size} songs?") },
            text = { Text("They'll show up again in search, home and radio.") },
            confirmButton = {
                TextButton(onClick = {
                    AppGraph.scope.launch { dao.clearDisliked() }
                    confirmRestoreAll = false
                }) { Text("Restore all") }
            },
            dismissButton = { TextButton(onClick = { confirmRestoreAll = false }) { Text("Cancel") } },
        )
    }

    menuSong?.let { s ->
        SongActionsSheet(
            s,
            onDismiss = { menuSong = null },
            onRemoveFromPlaylist = if (kind == "playlist") {
                { AppGraph.scope.launch { AppGraph.library.removeFromPlaylist(playlistId, s.id) } }
            } else null,
        )
    }
}

@Composable
internal fun CollectionHeader(
    title: String,
    subtitle: String,
    icon: ImageVector,
    cover: String?,
    gradient: List<Color>,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    enabled: Boolean,
    extraActions: @Composable RowScope.() -> Unit,
) {
    val bg = MaterialTheme.colorScheme.background
    Box(
        Modifier.fillMaxWidth().background(
            Brush.verticalGradient(listOf(gradient.first().copy(alpha = 0.75f), gradient.first().copy(alpha = 0.25f), bg))
        )
    ) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.offset(x = (-8).dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.align(Alignment.CenterHorizontally).size(180.dp)
                    .shadow(24.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(gradient)),
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null) Artwork(cover, Modifier.fillMaxSize(), 12.dp, 540)
                else Icon(icon, null, tint = Color.White, modifier = Modifier.size(72.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                extraActions()
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onShuffle, enabled = enabled) {
                    Icon(Icons.Rounded.Shuffle, "Shuffle", modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = onPlay,
                    enabled = enabled,
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Icon(Icons.Rounded.PlayArrow, "Play", modifier = Modifier.size(32.dp)) }
            }
        }
    }
}
