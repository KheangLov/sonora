package com.sonora.music.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Database(
    entities = [SongEntity::class, PlaylistEntity::class, PlaylistSongEntity::class, SearchEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun searchDao(): SearchDao
}

@Dao
interface SongDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(song: SongEntity): Long

    @Query(
        """UPDATE songs SET title = :title, artist = :artist,
           thumbnail = CASE WHEN :thumb = '' THEN thumbnail ELSE :thumb END,
           durationSec = CASE WHEN :dur > 0 THEN :dur ELSE durationSec END
           WHERE id = :id"""
    )
    suspend fun updateMeta(id: String, title: String, artist: String, thumb: String, dur: Long)

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun get(id: String): SongEntity?

    /** Called from ExoPlayer's loader thread — never the main thread. */
    @Query("SELECT * FROM songs WHERE id = :id")
    fun getBlocking(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE id = :id")
    fun observe(id: String): Flow<SongEntity?>

    @Query("SELECT * FROM songs WHERE liked = 1 ORDER BY likedAt DESC")
    fun observeLiked(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE downloadState != 0 ORDER BY downloadState = 3, downloadedAt DESC")
    fun observeDownloads(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun observeHistory(limit: Int): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT 1")
    suspend fun lastPlayed(): SongEntity?

    @Query("SELECT COUNT(*) FROM songs WHERE liked = 1")
    fun likedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM songs WHERE downloadState = 3")
    fun downloadCount(): Flow<Int>

    @Query("UPDATE songs SET liked = :liked, likedAt = :ts WHERE id = :id")
    suspend fun setLiked(id: String, liked: Boolean, ts: Long)

    @Query("UPDATE songs SET lastPlayedAt = :ts, playCount = playCount + 1 WHERE id = :id")
    suspend fun markPlayed(id: String, ts: Long)

    @Query("UPDATE songs SET lastPlayedAt = 0")
    suspend fun clearHistory()

    @Query("UPDATE songs SET downloadState = :state, downloadProgress = :progress WHERE id = :id")
    suspend fun setDownloadState(id: String, state: Int, progress: Int)

    @Query("UPDATE songs SET downloadState = 3, downloadProgress = 100, localPath = :path, downloadedAt = :ts WHERE id = :id")
    suspend fun setDownloaded(id: String, path: String, ts: Long)

    @Query("UPDATE songs SET downloadState = 0, downloadProgress = 0, localPath = NULL WHERE id = :id")
    suspend fun clearDownload(id: String)
}

@Dao
interface PlaylistDao {
    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :id")
    suspend fun clear(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSong(ref: PlaylistSongEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSong(playlistId: Long, songId: String)

    @Query(
        """SELECT p.*,
           (SELECT COUNT(*) FROM playlist_songs ps WHERE ps.playlistId = p.id) AS songCount,
           (SELECT s.thumbnail FROM playlist_songs ps2 JOIN songs s ON s.id = ps2.songId
              WHERE ps2.playlistId = p.id ORDER BY ps2.addedAt LIMIT 1) AS cover
           FROM playlists p ORDER BY p.createdAt DESC"""
    )
    fun observeAll(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: Long): Flow<PlaylistEntity?>

    @Query(
        """SELECT s.* FROM songs s JOIN playlist_songs ps ON ps.songId = s.id
           WHERE ps.playlistId = :id ORDER BY ps.addedAt"""
    )
    fun observeSongs(id: Long): Flow<List<SongEntity>>
}

@Dao
interface SearchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SearchEntity)

    @Query("SELECT * FROM search_history ORDER BY ts DESC LIMIT 12")
    fun observeRecent(): Flow<List<SearchEntity>>

    @Query("DELETE FROM search_history WHERE term = :term")
    suspend fun delete(term: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}
