@file:OptIn(ExperimentalFoundationApi::class)

package com.sonora.music.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonora.music.AppGraph
import com.sonora.music.data.db.PlaylistWithCount
import com.sonora.music.ui.components.Artwork
import com.sonora.music.ui.components.EmptyState
import com.sonora.music.ui.components.NameDialog
import kotlinx.coroutines.launch

val LikedGradient = listOf(Color(0xFF5B3FFF), Color(0xFF9D8CFF))
val DownloadsGradient = listOf(Color(0xFF5D7A00), Color(0xFFC6F432))
val HistoryGradient = listOf(Color(0xFFB4410E), Color(0xFFFF9A5A))

@Composable
fun LibraryScreen(
    bottomPadding: Dp,
    onOpenCollection: (String) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
) {
    val songs = AppGraph.db.songDao()
    val playlists by remember { AppGraph.db.playlistDao().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val likedCount by remember { songs.likedCount() }.collectAsStateWithLifecycle(0)
    val downloadCount by remember { songs.downloadCount() }.collectAsStateWithLifecycle(0)
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PlaylistWithCount?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
        item(key = "header") {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Your Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, "New playlist") }
            }
        }
        item(key = "liked") {
            LibraryTile("Liked Songs", "$likedCount songs", Icons.Rounded.Favorite, LikedGradient) { onOpenCollection("liked") }
        }
        item(key = "downloads") {
            LibraryTile("Downloads", "$downloadCount songs • available offline", Icons.Rounded.DownloadForOffline, DownloadsGradient) {
                onOpenCollection("downloads")
            }
        }
        item(key = "history") {
            LibraryTile("Recently played", "Your listening history", Icons.Rounded.History, HistoryGradient) { onOpenCollection("history") }
        }
        item(key = "playlists_header") {
            Text(
                "Playlists",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp),
            )
        }
        if (playlists.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Rounded.LibraryMusic,
                    title = "Make your first playlist",
                    message = "Long-press any song and choose \"Add to playlist\".",
                ) {
                    Button(onClick = { creating = true }) { Text("Create playlist") }
                }
            }
        } else {
            items(playlists, key = { it.playlist.id }) { p ->
                PlaylistRow(
                    p,
                    onClick = { onOpenPlaylist(p.playlist.id) },
                    onRename = { renaming = p },
                    onDelete = { AppGraph.scope.launch { AppGraph.library.deletePlaylist(p.playlist.id) } },
                )
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "New playlist",
            confirmLabel = "Create",
            onConfirm = { name -> AppGraph.scope.launch { AppGraph.library.createPlaylist(name) } },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { p ->
        NameDialog(
            title = "Rename playlist",
            initial = p.playlist.name,
            onConfirm = { name -> AppGraph.scope.launch { AppGraph.library.renamePlaylist(p.playlist.id, name) } },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun LibraryTile(title: String, subtitle: String, icon: ImageVector, gradient: List<Color>, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(gradient)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlaylistRow(p: PlaylistWithCount, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(p.cover, Modifier.size(60.dp), 8.dp, 200)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.playlist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Playlist • ${p.songCount} songs", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Rounded.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    onClick = { menu = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}
