package com.sonora.music.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sonora.music.data.model.Song

const val DL_NONE = 0
const val DL_QUEUED = 1
const val DL_RUNNING = 2
const val DL_DONE = 3
const val DL_FAILED = 4

@Entity(
    tableName = "songs",
    indices = [Index("liked"), Index("lastPlayedAt"), Index("downloadState")],
)
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val thumbnail: String,
    val durationSec: Long,
    val liked: Boolean = false,
    val likedAt: Long = 0,
    val lastPlayedAt: Long = 0,
    val playCount: Int = 0,
    val downloadState: Int = DL_NONE,
    val downloadProgress: Int = 0,
    val localPath: String? = null,
    val downloadedAt: Long = 0,
)

fun SongEntity.toSong() = Song(id, title, artist, thumbnail, durationSec)
val SongEntity.isDownloaded: Boolean get() = downloadState == DL_DONE

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    indices = [Index("songId")],
)
data class PlaylistSongEntity(
    val playlistId: Long,
    val songId: String,
    val addedAt: Long,
)

@Entity(tableName = "search_history")
data class SearchEntity(
    @PrimaryKey val term: String,
    val ts: Long,
)

data class PlaylistWithCount(
    @Embedded val playlist: PlaylistEntity,
    val songCount: Int,
    val cover: String?,
)
