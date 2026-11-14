package com.sonora.music.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Database(
    entities = [SongEntity::class, PlaylistEntity::class, PlaylistSongEntity::class, SearchEntity::class, LyricsEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun searchDao(): SearchDao
    abstract fun lyricsDao(): LyricsDao

    companion object {
        /** v2: dislikes + lyrics cache. Keeps your likes, playlists, history and downloads. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN disliked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE songs ADD COLUMN dislikedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS lyrics (id TEXT NOT NULL, synced TEXT, plain TEXT, " +
                        "source TEXT NOT NULL, fetchedAt INTEGER NOT NULL, PRIMARY KEY(id))"
                )
            }
        }
    }
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

    @Query("SELECT * FROM songs WHERE liked = 1 AND disliked = 0 ORDER BY likedAt DESC")
    fun observeLiked(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE downloadState != 0 ORDER BY downloadState = 3, downloadedAt DESC")
    fun observeDownloads(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE lastPlayedAt > 0 AND disliked = 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun observeHistory(limit: Int): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT 1")
    suspend fun lastPlayed(): SongEntity?

    @Query("SELECT COUNT(*) FROM songs WHERE liked = 1 AND disliked = 0")
    fun likedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM songs WHERE downloadState = 3")
    fun downloadCount(): Flow<Int>

    @Query("UPDATE songs SET liked = :liked, likedAt = :ts, disliked = CASE WHEN :liked THEN 0 ELSE disliked END WHERE id = :id")
    suspend fun setLiked(id: String, liked: Boolean, ts: Long)

    /** Disliking also removes the song from Liked Songs. */
    @Query("UPDATE songs SET disliked = :disliked, dislikedAt = :ts, liked = CASE WHEN :disliked THEN 0 ELSE liked END WHERE id = :id")
    suspend fun setDisliked(id: String, disliked: Boolean, ts: Long)

    @Query("SELECT * FROM songs WHERE disliked = 1 ORDER BY dislikedAt DESC")
    fun observeDisliked(): Flow<List<SongEntity>>

    @Query("SELECT id FROM songs WHERE disliked = 1")
    fun observeDislikedIds(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM songs WHERE disliked = 1")
    fun dislikedCount(): Flow<Int>

    @Query("UPDATE songs SET disliked = 0, dislikedAt = 0 WHERE disliked = 1")
    suspend fun clearDisliked()

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
           WHERE ps.playlistId = :id AND s.disliked = 0 ORDER BY ps.addedAt"""
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

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE id = :id")
    suspend fun get(id: String): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: LyricsEntity)

    @Query("DELETE FROM lyrics WHERE id = :id")
    suspend fun delete(id: String)
}
