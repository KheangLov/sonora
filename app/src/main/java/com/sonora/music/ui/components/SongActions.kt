@file:OptIn(ExperimentalMaterial3Api::class)

package com.sonora.music.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddToQueue
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonora.music.AppGraph
import com.sonora.music.data.db.DL_DONE
import com.sonora.music.data.db.DL_NONE
import com.sonora.music.data.db.DL_FAILED
import com.sonora.music.data.download.Downloads
import com.sonora.music.data.model.Song
import kotlinx.coroutines.launch

@Composable
fun SongActionsSheet(
    song: Song,
    onDismiss: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val conn = AppGraph.player
    val entity by remember(song.id) { AppGraph.db.songDao().observe(song.id) }
        .collectAsStateWithLifecycle(initialValue = null)
    var pickPlaylist by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(song.thumbnail, Modifier.size(56.dp), 6.dp, 200)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

        SheetAction(Icons.Rounded.QueuePlayNext, "Play next") {
            conn.playNext(song); context.toast("Playing next"); onDismiss()
        }
        SheetAction(Icons.Rounded.AddToQueue, "Add to queue") {
            conn.addToQueue(song); context.toast("Added to queue"); onDismiss()
        }
        SheetAction(Icons.Rounded.Radio, "Start radio") {
            conn.startRadioFrom(song); onDismiss()
        }
        val liked = entity?.liked == true
        SheetAction(
            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (liked) "Remove from Liked Songs" else "Add to Liked Songs",
            tint = if (liked) MaterialTheme.colorScheme.primary else null,
        ) {
            AppGraph.scope.launch { AppGraph.library.setLiked(song, !liked) }
            onDismiss()
        }
        val state = entity?.downloadState ?: DL_NONE
        when (state) {
            DL_DONE -> SheetAction(Icons.Rounded.DownloadDone, "Remove download", tint = MaterialTheme.colorScheme.primary) {
                Downloads.remove(context, song.id); onDismiss()
            }
            DL_NONE, DL_FAILED -> SheetAction(Icons.Rounded.Download, if (state == DL_FAILED) "Retry download" else "Download") {
                Downloads.download(context, song); context.toast("Downloading…"); onDismiss()
            }
            else -> SheetAction(Icons.Rounded.Download, "Downloading ${entity?.downloadProgress ?: 0}% — tap to cancel") {
                Downloads.remove(context, song.id); onDismiss()
            }
        }
        SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") { pickPlaylist = true }
        if (onRemoveFromPlaylist != null) {
            SheetAction(Icons.Rounded.RemoveCircleOutline, "Remove from this playlist") {
                onRemoveFromPlaylist(); onDismiss()
            }
        }
        SheetAction(Icons.Rounded.Share, "Share") {
            context.shareSong(song); onDismiss()
        }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }

    if (pickPlaylist) {
        AddToPlaylistDialog(song, onDone = { pickPlaylist = false; onDismiss() })
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, tint: Color? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint ?: MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun AddToPlaylistDialog(song: Song, onDone: () -> Unit) {
    val context = LocalContext.current
    val playlists by remember { AppGraph.db.playlistDao().observeAll() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDone,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(if (creating) "New playlist" else "Add to playlist") },
        text = {
            if (creating) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("Playlist name") },
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().clip6().clickable { creating = true }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(44.dp).clip6().background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.onPrimary) }
                            Spacer(Modifier.width(14.dp))
                            Text("New playlist", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    items(playlists, key = { it.playlist.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clip6().clickable {
                                AppGraph.scope.launch { AppGraph.library.addToPlaylist(p.playlist.id, song) }
                                context.toast("Added to ${p.playlist.name}")
                                onDone()
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(p.cover, Modifier.size(44.dp), 6.dp, 140)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(p.playlist.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text("${p.songCount} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val trimmed = name.trim()
                        AppGraph.scope.launch {
                            val id = AppGraph.library.createPlaylist(trimmed)
                            AppGraph.library.addToPlaylist(id, song)
                        }
                        context.toast("Added to $trimmed")
                        onDone()
                    },
                ) { Text("Create") }
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
fun NameDialog(
    title: String,
    initial: String = "",
    confirmLabel: String = "Save",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Playlist name") })
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()); onDismiss() }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun Modifier.clip6() = clip(RoundedCornerShape(6.dp))

fun Context.toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

fun Context.shareSong(song: Song) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "${song.title} — ${song.artist}\n${song.shareUrl}")
    startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
