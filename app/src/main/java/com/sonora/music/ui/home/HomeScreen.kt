@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.sonora.music.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sonora.music.AppGraph
import com.sonora.music.data.db.toSong
import com.sonora.music.data.model.HomeSection
import com.sonora.music.data.model.Song
import com.sonora.music.playback.PlayerState
import com.sonora.music.ui.components.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import com.sonora.music.data.youtube.YtCollection
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

data class Mood(val label: String, val query: String)

val Moods = listOf(
    Mood("Chill", "chill lofi songs"),
    Mood("Focus", "focus deep work instrumental"),
    Mood("Workout", "workout gym hits"),
    Mood("Party", "party dance hits"),
    Mood("Romance", "romantic love songs"),
    Mood("Sleep", "sleep calm piano"),
    Mood("Throwback", "2000s throwback hits"),
    Mood("K-Pop", "kpop hits"),
)

sealed interface HomeUi {
    data object Loading : HomeUi
    data object Error : HomeUi
    data class Content(val sections: List<HomeSection>) : HomeUi
}

class HomeViewModel : ViewModel() {
    private val repo = AppGraph.youtube
    private val lib = AppGraph.library

    val recent: StateFlow<List<Song>> = lib.songs.observeHistory(6)
        .map { list -> list.map { it.toSong() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val ui = MutableStateFlow<HomeUi>(HomeUi.Loading)
    val refreshing = MutableStateFlow(false)
    val mood = MutableStateFlow<Mood?>(null)
    val moodSongs = MutableStateFlow<List<Song>?>(null)

    init {
        refresh(force = false)
        // Signing in / out swaps between the generic and your personal YouTube Music home.
        viewModelScope.launch {
            AppGraph.account.state.map { it?.cookie }.distinctUntilChanged().drop(1).collect { refresh(force = true) }
        }
    }

    fun refresh(force: Boolean) {
        viewModelScope.launch {
            if (ui.value is HomeUi.Content) refreshing.value = true else ui.value = HomeUi.Loading
            val seed = lib.songs.lastPlayed()?.toSong()
            val result = runCatching { repo.home(seed, force) }.getOrDefault(emptyList())
            ui.value = when {
                result.isNotEmpty() -> HomeUi.Content(result)
                ui.value is HomeUi.Content -> ui.value
                else -> HomeUi.Error
            }
            refreshing.value = false
        }
    }

    fun selectMood(selected: Mood?) {
        if (selected == mood.value) return
        mood.value = selected
        moodSongs.value = null
        if (selected == null) return
        viewModelScope.launch {
            val songs = runCatching { repo.search(selected.query) }.getOrDefault(emptyList())
            if (mood.value == selected) moodSongs.value = songs
        }
    }
}

@Composable
fun HomeScreen(
    bottomPadding: Dp,
    onOpenCollection: (String) -> Unit,
    onOpenRemote: (YtCollection) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val account by AppGraph.account.state.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val mood by vm.mood.collectAsStateWithLifecycle()
    val moodSongs by vm.moodSongs.collectAsStateWithLifecycle()
    val player by AppGraph.player.state.collectAsStateWithLifecycle()
    val conn = AppGraph.player
    var menuSong by remember { mutableStateOf<Song?>(null) }

    Box(Modifier.fillMaxSize()) {
        // Ambient accent glow behind the header
        Box(
            Modifier.fillMaxWidth().height(340.dp).background(
                Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), Color.Transparent))
            )
        )
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { vm.refresh(force = true) },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
                item(key = "header") { HomeHeader(account?.name, onHistory = { onOpenCollection("history") }) }
                item(key = "moods") { MoodChips(mood, vm::selectMood) }

                val selectedMood = mood
                if (selectedMood != null) {
                    item(key = "mood_title") { SectionHeader(selectedMood.label, "Mood") }
                    val list = moodSongs
                    if (list == null) {
                        items(8) { SkeletonRow() }
                    } else if (list.isEmpty()) {
                        item { EmptyState(title = "Nothing here", message = "Couldn't load this mood. Check your connection.") }
                    } else {
                        itemsIndexed(list, key = { i, s -> "mood_${i}_${s.id}" }) { i, s ->
                            SongRow(
                                s,
                                onClick = { conn.play(list, i) },
                                isCurrent = player.current?.id == s.id,
                                isPlaying = player.isPlaying,
                                onMore = { menuSong = s },
                            )
                        }
                    }
                } else {
                    if (recent.isNotEmpty()) {
                        item(key = "recent") {
                            RecentGrid(recent, player, onPlay = conn::playRadio, onMore = { menuSong = it })
                        }
                    }
                    when (val state = ui) {
                        HomeUi.Loading -> item(key = "skeleton") { HomeSkeleton() }
                        HomeUi.Error -> item(key = "error") {
                            EmptyState(
                                title = "Can't reach YouTube",
                                message = "Check your connection. Your downloads still play offline.",
                            ) {
                                Button(onClick = { vm.refresh(force = true) }) { Text("Retry") }
                                OutlinedButton(onClick = { onOpenCollection("downloads") }) { Text("Downloads") }
                            }
                        }
                        is HomeUi.Content -> state.sections.forEachIndexed { index, section ->
                            item(key = "section_$index") {
                                if (section.collections.isEmpty() && section.songs.size >= 8 && (index == 0 || section.title.contains("pick", ignoreCase = true))) {
                                    QuickPicks(section, player, onPlay = conn::playRadio, onMore = { menuSong = it })
                                } else {
                                    SongCarousel(
                                        section, player,
                                        onPlay = { i -> conn.play(section.songs, i) },
                                        onMore = { menuSong = it },
                                        onOpenRemote = onOpenRemote,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    menuSong?.let { SongActionsSheet(it, onDismiss = { menuSong = null }) }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun HomeHeader(name: String?, onHistory: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                greeting() + (name?.substringBefore(' ')?.let { ", $it" } ?: ""),
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "What's the vibe today?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onHistory) { Icon(Icons.Rounded.History, "History") }
    }
}

@Composable
private fun MoodChips(selected: Mood?, onSelect: (Mood?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("For you") },
                shape = RoundedCornerShape(20.dp),
                colors = chipColors(),
                border = null,
            )
        }
        items(Moods, key = { it.label }) { m ->
            FilterChip(
                selected = selected == m,
                onClick = { onSelect(if (selected == m) null else m) },
                label = { Text(m.label) },
                shape = RoundedCornerShape(20.dp),
                colors = chipColors(),
                border = null,
            )
        }
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    labelColor = MaterialTheme.colorScheme.onSurface,
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
)

@Composable
private fun RecentGrid(songs: List<Song>, player: PlayerState, onPlay: (Song) -> Unit, onMore: (Song) -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        songs.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { s ->
                    val current = player.current?.id == s.id
                    Row(
                        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .combinedClickable(onClick = { onPlay(s) }, onLongClick = { onMore(s) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(s.thumbnail, Modifier.size(56.dp), 0.dp, 160)
                        Text(
                            s.title,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        )
                        if (current) {
                            PlayingBars(player.isPlaying, Modifier.padding(end = 10.dp).size(14.dp))
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun QuickPicks(section: HomeSection, player: PlayerState, onPlay: (Song) -> Unit, onMore: (Song) -> Unit) {
    SectionHeader(section.title, section.subtitle)
    LazyHorizontalGrid(
        rows = GridCells.Fixed(4),
        modifier = Modifier.fillMaxWidth().height((4 * 64).dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        items(section.songs.size, key = { section.songs[it].id }) { i ->
            val s = section.songs[i]
            val current = player.current?.id == s.id
            Row(
                Modifier.width(312.dp).height(64.dp).clip(RoundedCornerShape(8.dp))
                    .combinedClickable(onClick = { onPlay(s) }, onLongClick = { onMore(s) })
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp)) {
                    Artwork(s.thumbnail, Modifier.fillMaxSize(), 6.dp, 150)
                    if (current) {
                        Box(
                            Modifier.matchParentSize().clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center,
                        ) { PlayingBars(player.isPlaying, Modifier.size(16.dp)) }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        s.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        s.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onMore(s) }) {
                    Icon(Icons.Rounded.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SongCarousel(
    section: HomeSection,
    player: PlayerState,
    onPlay: (Int) -> Unit,
    onMore: (Song) -> Unit,
    onOpenRemote: (YtCollection) -> Unit,
) {
    SectionHeader(section.title, section.subtitle)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(section.collections, key = { "c_" + it.id }) { c ->
            CollectionCard(c, onClick = { onOpenRemote(c) })
        }
        itemsIndexed(section.songs, key = { _, s -> s.id }) { i, s ->
            SongCard(
                s,
                onClick = { onPlay(i) },
                onLongClick = { onMore(s) },
                isCurrent = player.current?.id == s.id,
                isPlaying = player.isPlaying,
            )
        }
    }
}

@Composable
private fun HomeSkeleton() {
    Column {
        Box(Modifier.padding(start = 16.dp, top = 28.dp, bottom = 12.dp).width(160.dp).height(22.dp).shimmer(4.dp))
        repeat(4) { SkeletonRow() }
        Box(Modifier.padding(start = 16.dp, top = 28.dp, bottom = 12.dp).width(140.dp).height(22.dp).shimmer(4.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(3) { Box(Modifier.size(148.dp).shimmer(10.dp)) }
        }
    }
}

@Composable
private fun CollectionCard(c: YtCollection, onClick: () -> Unit) {
    val isArtist = c.id.startsWith("UC")
    Column(
        Modifier.width(148.dp).clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = onClick),
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Artwork(c.thumbnail, Modifier.size(148.dp), if (isArtist) 74.dp else 10.dp, 420)
        Spacer(Modifier.height(8.dp))
        Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            c.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
