@file:OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)

package com.sonora.music.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sonora.music.AppGraph
import com.sonora.music.data.filter.ContentFilter
import com.sonora.music.data.model.Song
import com.sonora.music.ui.components.rememberVisibleSongs
import com.sonora.music.ui.components.EmptyState
import com.sonora.music.ui.components.SkeletonRow
import com.sonora.music.ui.components.SongActionsSheet
import com.sonora.music.ui.components.SongRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SearchResults {
    data object Idle : SearchResults
    data object Loading : SearchResults
    data object Error : SearchResults
    data class Success(
        val songs: List<Song>,
        val hasMore: Boolean = false,
        val loadingMore: Boolean = false,
        val loadMoreFailed: Boolean = false,
        val correctedTo: String? = null,
        val didYouMean: String? = null,
        val blocked: Boolean = false,
    ) : SearchResults
}

class SearchViewModel : ViewModel() {
    private val repo = AppGraph.youtube
    private val lib = AppGraph.library

    val query = MutableStateFlow("")
    val submitted = MutableStateFlow<String?>(null)
    val results = MutableStateFlow<SearchResults>(SearchResults.Idle)
    private var searchJob: Job? = null

    val history: StateFlow<List<String>> = lib.searches.observeRecent()
        .map { list -> list.map { it.term }.filterNot(ContentFilter::blocksQuery) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val suggestions: StateFlow<List<String>> = query
        .debounce(220)
        .distinctUntilChanged()
        .mapLatest { q ->
            if (q.isBlank() || q == submitted.value) emptyList()
            else runCatching { repo.suggestions(q) }.getOrDefault(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQuery(q: String) {
        query.value = q
    }

    fun submit(q: String) {
        val term = q.trim()
        if (term.isEmpty()) return
        query.value = term
        submitted.value = term
        results.value = SearchResults.Loading
        searchJob?.cancel()
        session = null
        searchJob = viewModelScope.launch {
            if (!ContentFilter.blocksQuery(term)) lib.addSearch(term)
            results.value = runCatching { repo.startSearch(term) }.fold(
                { r ->
                    session = r.session
                    SearchResults.Success(
                        songs = r.songs,
                        hasMore = r.session.hasMore,
                        correctedTo = r.correctedTo,
                        didYouMean = r.didYouMean,
                        blocked = r.blocked,
                    )
                },
                { SearchResults.Error },
            )
        }
    }

    private var session: com.sonora.music.data.youtube.YouTubeRepository.SearchSession? = null

    fun loadMore() {
        val current = results.value as? SearchResults.Success ?: return
        val s = session ?: return
        if (current.loadingMore || !current.hasMore) return
        results.value = current.copy(loadingMore = true, loadMoreFailed = false)
        searchJob = viewModelScope.launch {
            val more = runCatching { repo.loadMore(s) }
            if (session !== s) return@launch // a new search started meanwhile
            val latest = results.value as? SearchResults.Success ?: return@launch
            results.value = more.fold(
                { list ->
                    latest.copy(songs = latest.songs + list, loadingMore = false, hasMore = s.hasMore)
                },
                { latest.copy(loadingMore = false, loadMoreFailed = true) },
            )
            // A page can be entirely filtered out as duplicates — keep going automatically.
            val now = results.value as? SearchResults.Success
            if (more.isSuccess && more.getOrNull().isNullOrEmpty() && now?.hasMore == true) loadMore()
        }
    }

    fun removeHistory(term: String) = viewModelScope.launch { lib.searches.delete(term) }
    fun clearHistory() = viewModelScope.launch { lib.searches.clear() }
}

private data class Genre(val label: String, val query: String, val color: Color)

private val Genres = listOf(
    Genre("Pop", "pop hits", Color(0xFFE8115B)),
    Genre("Hip-Hop", "hip hop hits", Color(0xFFBA5D07)),
    Genre("Lo-fi", "lofi beats", Color(0xFF477D95)),
    Genre("K-Pop", "kpop hits", Color(0xFF8D67AB)),
    Genre("R&B", "r&b hits", Color(0xFFDC148C)),
    Genre("Rock", "rock classics", Color(0xFF7D4B32)),
    Genre("EDM", "edm festival hits", Color(0xFF1E3264)),
    Genre("Acoustic", "acoustic covers", Color(0xFF608108)),
    Genre("Jazz", "jazz classics", Color(0xFF8C1932)),
    Genre("Anime", "anime openings", Color(0xFFE91429)),
    Genre("Latin", "latin hits", Color(0xFF0D73EC)),
    Genre("Khmer", "khmer songs", Color(0xFF148A08)),
)

@Composable
fun SearchScreen(bottomPadding: Dp, vm: SearchViewModel = viewModel()) {
    val query by vm.query.collectAsStateWithLifecycle()
    val submitted by vm.submitted.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val player by AppGraph.player.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var menuSong by remember { mutableStateOf<Song?>(null) }

    val submit: (String) -> Unit = {
        vm.submit(it)
        focus.clearFocus()
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
            Text("Search", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
            SearchField(query, onChange = vm::onQuery, onSearch = submit)
        }

        val showResults = submitted != null && submitted == query
        val visibleResults = rememberVisibleSongs((results as? SearchResults.Success)?.songs.orEmpty())
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
            when {
                showResults -> when (val r = results) {
                    SearchResults.Idle -> Unit
                    SearchResults.Loading -> items(10) { SkeletonRow() }
                    SearchResults.Error -> item {
                        EmptyState(title = "Search failed", message = "Check your connection and try again.") {
                            Button(onClick = { submit(query) }) { Text("Retry") }
                        }
                    }
                    is SearchResults.Success ->
                        if (r.blocked) {
                            item {
                                EmptyState(Icons.Rounded.Block, "This search is blocked", "Thai / Thailand content is filtered out of Sonora.")
                            }
                        } else if (visibleResults.isEmpty() && !r.hasMore) {
                            item {
                                EmptyState(Icons.Rounded.SearchOff, "No results for \"$query\"", "Try different keywords or check the spelling.")
                            }
                        } else {
                            val hint = r.correctedTo?.let { "Showing results for" to it }
                                ?: r.didYouMean?.let { "Did you mean" to it }
                            if (hint != null) {
                                item(key = "spell") {
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable(enabled = r.didYouMean != null) { submit(hint.second) }
                                            .padding(horizontal = 20.dp, vertical = 10.dp),
                                    ) {
                                        Text(
                                            "${hint.first} ",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            hint.second + if (r.didYouMean != null) "?" else "",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                            if (visibleResults.isEmpty() && r.hasMore && !r.loadingMore && !r.loadMoreFailed) {
                                // Everything on this page was filtered out — fetch the next one.
                                item(key = "autoload") { LaunchedEffect(r.songs.size) { vm.loadMore() } }
                            }
                            itemsIndexed(visibleResults, key = { _, s -> "r_${s.id}" }) { i, s ->
                                // Infinite scroll: prefetch when 6 rows from the end
                                if (i >= visibleResults.size - 6 && r.hasMore && !r.loadingMore && !r.loadMoreFailed) {
                                    LaunchedEffect(r.songs.size) { vm.loadMore() }
                                }
                                SongRow(
                                    s,
                                    onClick = { AppGraph.player.playRadio(s) },
                                    isCurrent = player.current?.id == s.id,
                                    isPlaying = player.isPlaying,
                                    onMore = { menuSong = s },
                                )
                            }
                            item(key = "footer") {
                                Box(
                                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    when {
                                        r.loadingMore -> CircularProgressIndicator(
                                            Modifier.size(28.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            strokeWidth = 3.dp,
                                        )
                                        r.loadMoreFailed -> OutlinedButton(onClick = { vm.loadMore() }) {
                                            Text("Couldn't load more · Retry")
                                        }
                                        r.hasMore -> OutlinedButton(onClick = { vm.loadMore() }) { Text("Load more") }
                                        else -> Text(
                                            "That's everything for \"$query\"",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                }

                query.isNotBlank() -> items(suggestions, key = { "s_$it" }) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { submit(s) }.padding(start = 20.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Text(s, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(vertical = 14.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { vm.onQuery("$s ") }) {
                            Icon(Icons.Rounded.NorthWest, "Fill", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                else -> {
                    if (history.isNotEmpty()) {
                        item(key = "history_header") {
                            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Recent searches", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = { vm.clearHistory() }) { Text("Clear") }
                            }
                        }
                        items(history, key = { "h_$it" }) { term ->
                            Row(
                                Modifier.fillMaxWidth().clickable { submit(term) }.padding(start = 20.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(16.dp))
                                Text(term, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(vertical = 12.dp), maxLines = 1)
                                IconButton(onClick = { vm.removeHistory(term) }) {
                                    Icon(Icons.Rounded.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item(key = "browse_header") {
                        Text(
                            "Browse all",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 12.dp),
                        )
                    }
                    items(Genres.chunked(2), key = { "g_${it.first().label}" }) { row ->
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            row.forEach { g -> GenreTile(g, Modifier.weight(1f)) { submit(g.query) } }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    menuSong?.let { SongActionsSheet(it, onDismiss = { menuSong = null }) }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, onSearch: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Songs, artists, vibes…") },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "Clear") }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
    )
}

@Composable
private fun GenreTile(genre: Genre, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(96.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(listOf(genre.color, genre.color.copy(alpha = 0.55f))))
            .clickable(onClick = onClick),
    ) {
        Text(
            genre.label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(12.dp),
        )
        Icon(
            Icons.Rounded.MusicNote,
            null,
            tint = Color.White.copy(alpha = 0.28f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 4.dp).size(52.dp).rotate(22f),
        )
    }
}
