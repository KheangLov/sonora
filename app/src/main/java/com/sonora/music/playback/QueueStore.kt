package com.sonora.music.playback

import android.content.Context
import androidx.media3.common.Player
import com.sonora.music.data.model.Song
import org.json.JSONArray
import org.json.JSONObject

/** Persists the queue + position so the app (and headset/Bluetooth play) resumes where you left off. */
object QueueStore {
    data class Saved(val songs: List<Song>, val index: Int, val positionMs: Long)

    private const val MAX = 300

    private fun prefs(context: Context) = context.getSharedPreferences("playback_queue", Context.MODE_PRIVATE)

    fun save(context: Context, player: Player) {
        val count = player.mediaItemCount
        if (count == 0) return
        val current = player.currentMediaItemIndex.coerceAtLeast(0)
        val start = (current - 100).coerceAtLeast(0)
        val end = minOf(count, start + MAX)
        val array = JSONArray()
        for (i in start until end) {
            val s = player.getMediaItemAt(i).toSong()
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("t", s.title)
                    .put("a", s.artist)
                    .put("th", s.thumbnail)
                    .put("d", s.durationSec)
            )
        }
        prefs(context).edit()
            .putString("items", array.toString())
            .putInt("index", current - start)
            .putLong("pos", player.currentPosition.coerceAtLeast(0))
            .apply()
    }

    fun load(context: Context): Saved? {
        val p = prefs(context)
        val raw = p.getString("items", null) ?: return null
        return runCatching {
            val array = JSONArray(raw)
            val songs = List(array.length()) { i ->
                val o = array.getJSONObject(i)
                Song(o.getString("id"), o.optString("t"), o.optString("a"), o.optString("th"), o.optLong("d"))
            }
            if (songs.isEmpty()) null
            else Saved(songs, p.getInt("index", 0).coerceIn(songs.indices), p.getLong("pos", 0L))
        }.getOrNull()
    }
}
