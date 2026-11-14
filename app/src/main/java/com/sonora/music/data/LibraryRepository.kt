package com.sonora.music.data

import com.sonora.music.data.db.AppDatabase
import com.sonora.music.data.db.PlaylistEntity
import com.sonora.music.data.db.PlaylistSongEntity
import com.sonora.music.data.db.SearchEntity
import com.sonora.music.data.db.SongEntity
import com.sonora.music.data.model.Song

/** Everything the user owns locally: likes, history, playlists, searches. */
class LibraryRepository(db: AppDatabase) {
    val songs = db.songDao()
    val playlists = db.playlistDao()
    val searches = db.searchDao()

    private fun now() = System.currentTimeMillis()

    suspend fun ensure(song: Song) {
        songs.insertIgnore(SongEntity(song.id, song.title, song.artist, song.thumbnail, song.durationSec))
        if (song.title.isNotBlank()) {
            songs.updateMeta(song.id, song.title, song.artist, song.thumbnail, song.durationSec)
        }
    }

    suspend fun markPlayed(song: Song) {
        ensure(song)
        songs.markPlayed(song.id, now())
    }

    suspend fun setLiked(song: Song, liked: Boolean) {
        ensure(song)
        songs.setLiked(song.id, liked, now())
    }

    suspend fun setDisliked(song: Song, disliked: Boolean) {
        ensure(song)
        songs.setDisliked(song.id, disliked, now())
    }

    suspend fun createPlaylist(name: String): Long =
        playlists.insert(PlaylistEntity(name = name, createdAt = now()))

    suspend fun addToPlaylist(playlistId: Long, song: Song) {
        ensure(song)
        playlists.addSong(PlaylistSongEntity(playlistId, song.id, now()))
    }

    suspend fun removeFromPlaylist(playlistId: Long, songId: String) =
        playlists.removeSong(playlistId, songId)

    suspend fun renamePlaylist(id: Long, name: String) = playlists.rename(id, name)

    suspend fun deletePlaylist(id: Long) {
        playlists.clear(id)
        playlists.delete(id)
    }

    suspend fun addSearch(term: String) {
        if (term.isNotBlank()) searches.upsert(SearchEntity(term.trim(), now()))
    }
}
