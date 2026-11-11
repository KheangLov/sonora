package com.sonora.music.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonora.music.AppGraph
import com.sonora.music.data.download.Downloads
import com.sonora.music.data.model.Song
import com.sonora.music.data.model.formatDuration
import com.sonora.music.data.youtube.YtCollection
import com.sonora.music.ui.components.EmptyState
import com.sonora.music.ui.components.SkeletonRow
import com.sonora.music.ui.components.SongActionsSheet
import com.sonora.music.ui.components.SongRow
import com.sonora.music.ui.components.toast
import com.sonora.music.ui.player.rememberArtworkColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Any YouTube Music playlist / album / artist / mix, opened as a playable list. */
@Composable
fun RemoteCollectionScreen(collection: YtCollection, bottomPadding: Dp, onBack: () -> Unit) {
    val context = LocalContext.current
    val conn = AppGraph.player
    val player by conn.state.collectAsStateWithLifecycle()
    var attempt by remember { mutableIntStateOf(0) }
    var menuSong by remember { mutableStateOf<Song?>(null) }

    val songs by produceState<List<Song>?>(initialValue = null, collection.id, attempt) {
        value = null
        value = runCatching { withContext(Dispatchers.IO) { AppGraph.ytMusic.collectionSongs(collection) } }
            .getOrElse { emptyList() }
    }
    val accent by rememberArtworkColor(collection.thumbnail.ifBlank { null }, Color(0xFF5B3FFF))
    val list = songs.orEmpty()
    val totalMs = list.sumOf { it.durationSec } * 1000

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
        item(key = "header") {
            CollectionHeader(
                title = collection.title,
                subtitle = when {
                    songs == null -> collection.subtitle
                    else -> buildString {
                        append("${list.size} songs")
                        if (totalMs > 0) append(" • ${formatDuration(totalMs)}")
                    }
                },
                icon = Icons.Rounded.LibraryMusic,
                cover = collection.thumbnail.ifBlank { null },
                gradient = listOf(lerp(accent, Color.Black, 0.2f), accent),
                onBack = onBack,
                onPlay = { conn.play(list) },
                onShuffle = { conn.play(list, shuffle = true) },
                enabled = list.isNotEmpty(),
                extraActions = {
                    IconButton(
                        enabled = list.isNotEmpty(),
                        onClick = {
                            AppGraph.scope.launch {
                                val lib = AppGraph.library
                                val id = lib.createPlaylist(collection.title)
                                list.forEach { lib.addToPlaylist(id, it) }
                            }
                            context.toast("Saved \"${collection.title}\" to your library")
                        },
                    ) { Icon(Icons.Rounded.LibraryAdd, "Save to library") }
                    IconButton(
                        enabled = list.isNotEmpty(),
                        onClick = {
                            Downloads.downloadAll(context, list)
                            context.toast("Downloading ${list.size} songs")
                        },
                    ) { Icon(Icons.Rounded.Download, "Download all") }
                },
            )
        }
        when {
            songs == null -> items(10) { SkeletonRow() }
            list.isEmpty() -> item {
                EmptyState(title = "Couldn't load this", message = "Check your connection, or sign in again from Library.") {
                    Button(onClick = { attempt++ }) { Text("Retry") }
                }
            }
            else -> itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                SongRow(
                    s,
                    onClick = { conn.play(list, i) },
                    isCurrent = player.current?.id == s.id,
                    isPlaying = player.isPlaying,
                    onMore = { menuSong = s },
                )
            }
        }
    }

    menuSong?.let { SongActionsSheet(it, onDismiss = { menuSong = null }) }
}
