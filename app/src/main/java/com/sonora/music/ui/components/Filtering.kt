package com.sonora.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonora.music.data.filter.ContentFilter
import com.sonora.music.data.model.Song

/** [songs] minus anything blocked or disliked. Updates instantly when you dislike / undislike. */
@Composable
fun rememberVisibleSongs(songs: List<Song>): List<Song> {
    val disliked by ContentFilter.dislikedIds.collectAsStateWithLifecycle()
    return remember(songs, disliked) { ContentFilter.filterSongs(songs) }
}
