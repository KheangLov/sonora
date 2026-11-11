package com.sonora.music.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import coil.imageLoader
import coil.request.ImageRequest
import com.sonora.music.AppGraph
import com.sonora.music.R
import com.sonora.music.data.model.Song
import com.sonora.music.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private val KEY_TITLE = stringPreferencesKey("title")
private val KEY_ARTIST = stringPreferencesKey("artist")
private val KEY_ART = stringPreferencesKey("art")
private val KEY_SONG = stringPreferencesKey("song")
private val KEY_PLAYING = booleanPreferencesKey("playing")
private val CMD = ActionParameters.Key<String>("cmd")

private val Lime = Color(0xFFC6F432)

/** Home-screen "now playing" widget: artwork, title, artist, prev / play-pause / next. */
class SonoraWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetContent(context) }
    }

    @Composable
    private fun WidgetContent(context: Context) {
        val prefs = currentState<Preferences>()
        val title = prefs[KEY_TITLE] ?: "Sonora"
        val artist = prefs[KEY_ARTIST] ?: "Tap to start listening"
        val playing = prefs[KEY_PLAYING] ?: false
        val art: Bitmap? = prefs[KEY_ART]?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }

        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_PLAYER, prefs[KEY_SONG] != null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        Row(
            GlanceModifier
                .fillMaxSize()
                .cornerRadius(24.dp)
                .background(Color(0xFF141418))
                .padding(10.dp)
                .clickable(actionStartActivity(open)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = art?.let { ImageProvider(it) } ?: ImageProvider(R.drawable.widget_art_placeholder),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.size(64.dp).cornerRadius(14.dp),
            )
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    title,
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                )
                Text(
                    artist,
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(Color(0xFFA1A1AA)), fontSize = 13.sp),
                )
                Spacer(GlanceModifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ControlIcon(R.drawable.ic_w_prev, "Previous", "prev")
                    Spacer(GlanceModifier.width(14.dp))
                    Box(
                        GlanceModifier
                            .size(38.dp)
                            .cornerRadius(19.dp)
                            .background(Lime)
                            .clickable(actionRunCallback<WidgetAction>(actionParametersOf(CMD to "toggle"))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            ImageProvider(if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play),
                            contentDescription = if (playing) "Pause" else "Play",
                            modifier = GlanceModifier.size(22.dp),
                        )
                    }
                    Spacer(GlanceModifier.width(14.dp))
                    ControlIcon(R.drawable.ic_w_next, "Next", "next")
                }
            }
        }
    }

    @Composable
    private fun ControlIcon(res: Int, label: String, cmd: String) {
        Image(
            ImageProvider(res),
            contentDescription = label,
            modifier = GlanceModifier
                .size(30.dp)
                .padding(3.dp)
                .clickable(actionRunCallback<WidgetAction>(actionParametersOf(CMD to cmd))),
        )
    }
}

class SonoraWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SonoraWidget()
}

/** Widget buttons talk to the same in-process player connection the UI uses. */
class WidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val cmd = parameters[CMD] ?: return
        withContext(Dispatchers.Main) {
            val player = AppGraph.player
            when (cmd) {
                "toggle" -> player.togglePlay()
                "next" -> player.next()
                "prev" -> player.previous()
            }
        }
    }
}

object WidgetUpdater {
    @Volatile private var artFor: String? = null

    suspend fun update(context: Context, song: Song?, playing: Boolean) {
        val manager = GlanceAppWidgetManager(context)
        val ids = manager.getGlanceIds(SonoraWidget::class.java)
        if (ids.isEmpty()) return

        val artFile = File(context.filesDir, "widget_art.jpg")
        if (song != null && song.id != artFor && song.thumbnail.isNotBlank()) {
            withContext(Dispatchers.IO) {
                val result = context.imageLoader.execute(
                    ImageRequest.Builder(context).data(song.thumbnail).size(256).allowHardware(false).build()
                )
                (result.drawable as? BitmapDrawable)?.bitmap?.let { bmp ->
                    FileOutputStream(artFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    artFor = song.id
                }
            }
        }

        val widget = SonoraWidget()
        ids.forEach { id ->
            updateAppWidgetState(context, id) { prefs ->
                if (song == null) {
                    prefs.remove(KEY_SONG)
                    prefs.remove(KEY_ART)
                } else {
                    prefs[KEY_SONG] = song.id
                    prefs[KEY_TITLE] = song.title
                    prefs[KEY_ARTIST] = song.artist
                    if (artFor == song.id) prefs[KEY_ART] = artFile.absolutePath else prefs.remove(KEY_ART)
                }
                prefs[KEY_PLAYING] = playing
            }
            widget.update(context, id)
        }
    }
}
