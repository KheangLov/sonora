package com.sonora.music.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnail: String,
    val durationSec: Long,
) {
    val durationText: String get() = formatDuration(durationSec * 1000)
    val shareUrl: String get() = "https://music.youtube.com/watch?v=$id"
}

@Immutable
data class HomeSection(
    val title: String,
    val subtitle: String?,
    val songs: List<Song>,
)

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
