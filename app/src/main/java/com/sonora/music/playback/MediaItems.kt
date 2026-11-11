package com.sonora.music.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.sonora.music.data.model.Song

private const val EXTRA_DURATION = "sonora.duration"

/** Songs are addressed as yt://song/<videoId>; YtDataSource resolves them lazily. */
fun songUri(id: String): Uri = Uri.parse("yt://song/$id")

fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri(songUri(id))
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setArtworkUri(thumbnail.takeIf { it.isNotBlank() }?.let(Uri::parse))
            .setExtras(Bundle().apply { putLong(EXTRA_DURATION, durationSec) })
            .build()
    )
    .build()

/** Controllers strip local configuration when sending items to the session; restore it. */
fun MediaItem.withPlaybackUri(): MediaItem = buildUpon().setUri(songUri(mediaId)).build()

fun MediaItem.toSong(): Song = Song(
    id = mediaId,
    title = mediaMetadata.title?.toString().orEmpty(),
    artist = mediaMetadata.artist?.toString().orEmpty(),
    thumbnail = mediaMetadata.artworkUri?.toString().orEmpty(),
    durationSec = mediaMetadata.extras?.getLong(EXTRA_DURATION) ?: 0L,
)
