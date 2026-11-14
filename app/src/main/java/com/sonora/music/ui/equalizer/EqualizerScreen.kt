@file:OptIn(ExperimentalMaterial3Api::class)

package com.sonora.music.ui.equalizer

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonora.music.AppGraph
import com.sonora.music.playback.fx.AudioFx
import com.sonora.music.playback.fx.BuiltInPresets
import com.sonora.music.playback.fx.EqPreset
import com.sonora.music.playback.fx.FxSettings
import com.sonora.music.playback.fx.bandLabel
import com.sonora.music.ui.player.MiniPlayer
import com.sonora.music.ui.theme.Accent
import com.sonora.music.ui.theme.Background
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

private fun fmt(v: Float, digits: Int = 1): String {
    val x = if (abs(v) < 0.5f * 0.1f.pow(digits)) 0f else v
    return String.format(Locale.US, "%.${digits}f", x)
}

/**
 * Poweramp-inspired audio screen: a 10-band graphic EQ with preamp, live spectrum + response
 * curve, presets, bass/treble and limiter on one tab; balance, stereo width, mono, tempo and
 * volume knobs on the other.
 */
@Composable
fun EqualizerScreen(onClose: () -> Unit) {
    val settings by AudioFx.settings.collectAsStateWithLifecycle()
    val playerState by AppGraph.player.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .pointerInput(Unit) { detectTapGestures { } } // don't let taps fall through to the player below
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top bar: back + tab switcher
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            TabButton(Icons.Rounded.Equalizer, "Equalizer", tab == 0, Modifier.weight(1f)) { tab = 0 }
            TabButton(Icons.Rounded.Tune, "Effects", tab == 1, Modifier.weight(1f)) { tab = 1 }
        }

        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
            label = "fxTab",
            modifier = Modifier.weight(1f),
        ) { t ->
            if (t == 0) EqTab(settings) else EffectsTab(settings)
        }

        if (playerState.current != null) {
            MiniPlayer(playerState, onExpand = onClose, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun TabButton(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, tween(220), label = "tabBg")
    val fg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(220), label = "tabFg",
    )
    Row(
        modifier
            .padding(horizontal = 4.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

// ================================================================== Equalizer tab

@Composable
private fun EqTab(s: FxSettings) {
    var showPresets by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        // Faders: preamp panel + 10 bands
        Row(Modifier.fillMaxWidth().height(320.dp)) {
            FaderColumn(
                label = "Preamp",
                value = s.preamp,
                enabled = s.eqEnabled,
                onChange = AudioFx::setPreamp,
                modifier = Modifier
                    .width(64.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 2.dp),
            ) {
                FxSettings.BAND_FREQS.forEachIndexed { i, hz ->
                    FaderColumn(
                        label = bandLabel(hz),
                        value = s.bands[i],
                        enabled = s.eqEnabled,
                        onChange = { AudioFx.setBand(i, it) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        ResponseGraph(
            s,
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )

        // Current preset chip
        Row(
            Modifier
                .padding(top = 12.dp)
                .align(Alignment.CenterHorizontally)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { showPresets = true }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(s.presetName.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelLarge, letterSpacing = 0.5.sp)
        }

        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth()) {
            // Section toggles
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FxPill("Equ", { AudioFx.update { it.copy(eqEnabled = !it.eqEnabled) } }, Modifier.width(92.dp), active = s.eqEnabled)
                FxPill("Tone", { AudioFx.update { it.copy(toneEnabled = !it.toneEnabled) } }, Modifier.width(92.dp), active = s.toneEnabled)
                FxPill("Limit", { AudioFx.update { it.copy(limiterEnabled = !it.limiterEnabled) } }, Modifier.width(92.dp), active = s.limiterEnabled)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FxPill("Presets", { showPresets = true }, Modifier.weight(1.2f), outlined = true)
                    FxPill("Save", { showSave = true }, Modifier.weight(1f), outlined = false)
                    FxPill("Reset", AudioFx::resetEq, Modifier.weight(1f), outlined = true)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    LabeledKnob("Bass", "${(s.bass * 100).roundToInt()}%", s.bass, s.toneEnabled, 96.dp) { v ->
                        AudioFx.update { it.copy(bass = v, toneEnabled = true) }
                    }
                    LabeledKnob("Treble", "${(s.treble * 100).roundToInt()}%", s.treble, s.toneEnabled, 96.dp) { v ->
                        AudioFx.update { it.copy(treble = v, toneEnabled = true) }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (showPresets) PresetSheet(s.presetName, onDismiss = { showPresets = false })
    if (showSave) SavePresetDialog(
        initial = s.presetName.takeIf { it != FxSettings.CUSTOM && BuiltInPresets.none { p -> p.name == it } }.orEmpty(),
        onDismiss = { showSave = false },
    )
}

@Composable
private fun FaderColumn(label: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit, modifier: Modifier) {
    Column(modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FxSlider(
            value = value,
            min = -FxSettings.MAX_DB,
            max = FxSettings.MAX_DB,
            onChange = onChange,
            enabled = enabled,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        Text(
            fmt(value),
            style = MaterialTheme.typography.labelSmall,
            color = if (abs(value) >= 0.05f) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun LabeledKnob(
    label: String,
    valueText: String,
    value: Float,
    enabled: Boolean,
    size: androidx.compose.ui.unit.Dp,
    bipolar: Boolean = false,
    color: Color? = null,
    onChange: (Float) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(valueText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        FxKnob(
            value = value,
            onChange = onChange,
            size = size,
            bipolar = bipolar,
            enabled = enabled,
            color = color ?: heatColor(if (bipolar) abs(value - 0.5f) * 2 else value),
        )
    }
}

// ================================================================== Presets

@Composable
private fun PresetSheet(current: String, onDismiss: () -> Unit) {
    val custom by AudioFx.customPresets.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Text("Presets", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
        LazyColumn(Modifier.fillMaxHeight(0.75f)) {
            if (custom.isNotEmpty()) {
                item { SectionLabel("Your presets") }
                items(custom, key = { "c_" + it.name }) { p ->
                    PresetRow(p, p.name == current, onPick = { AudioFx.applyPreset(p); onDismiss() }, onDelete = { AudioFx.deletePreset(p.name) })
                }
                item { SectionLabel("Built-in") }
            }
            items(BuiltInPresets, key = { "b_" + it.name }) { p ->
                PresetRow(p, p.name == current, onPick = { AudioFx.applyPreset(p); onDismiss() }, onDelete = null)
            }
            item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun PresetRow(p: EqPreset, selected: Boolean, onPick: () -> Unit, onDelete: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PresetThumb(
            p.bands, p.preamp,
            Modifier
                .size(width = 64.dp, height = 36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(4.dp),
            color = if (selected) Accent else Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            p.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) Icon(Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SavePresetDialog(initial: String, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Save preset") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(32) },
                singleLine = true,
                placeholder = { Text("My preset") },
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { AudioFx.savePreset(name); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ================================================================== Effects tab

@Composable
private fun EffectsTab(s: FxSettings) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            KnobBelow("Balance", fmt(s.balance, 2), (s.balance + 1f) / 2f, 132.dp, bipolar = true, color = Teal) { v ->
                val b = v * 2f - 1f
                AudioFx.update { it.copy(balance = if (abs(b) < 0.02f) 0f else b) }
            }
            KnobBelow("Stereo Expand", "${(s.stereoExpand * 100).roundToInt()}%", s.stereoExpand, 132.dp, enabled = !s.mono) { v ->
                AudioFx.update { it.copy(stereoExpand = v) }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Tempo
        Box(Modifier.fillMaxWidth()) {
            FxPill("Tempo", {}, Modifier.align(Alignment.TopStart), outlined = false)
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                FxKnob(
                    value = tempoToKnob(s.tempo),
                    onChange = { v -> AudioFx.update { it.copy(tempo = knobToTempo(v)) } },
                    size = 132.dp,
                    bipolar = true,
                    color = Sky,
                )
                Text("${fmt(s.tempo, 2)}x", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.align(Alignment.CenterEnd), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StepButton(Icons.Rounded.Add, "Faster") { AudioFx.update { it.copy(tempo = stepTempo(it.tempo, +1)) } }
                StepButton(Icons.Rounded.Remove, "Slower") { AudioFx.update { it.copy(tempo = stepTempo(it.tempo, -1)) } }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            FxPill("Mono", { AudioFx.update { it.copy(mono = !it.mono) } }, Modifier.width(96.dp), active = s.mono)
            FxPill("Reset", AudioFx::resetEffects, Modifier.width(96.dp), outlined = true)
        }

        Spacer(Modifier.height(12.dp))
        VolumeKnob()
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun KnobBelow(
    label: String,
    valueText: String,
    value: Float,
    size: androidx.compose.ui.unit.Dp,
    bipolar: Boolean = false,
    enabled: Boolean = true,
    color: Color? = null,
    onChange: (Float) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(if (enabled) 1f else 0.5f)) {
        FxKnob(
            value = value,
            onChange = onChange,
            size = size,
            bipolar = bipolar,
            enabled = enabled,
            color = color ?: heatColor(value),
        )
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StepButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 64.dp, height = 40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(20.dp)) }
}

/** Knob is bipolar around 1.00x: left half covers 0.5–1.0x, right half 1.0–2.0x. */
private fun tempoToKnob(t: Float) = if (t <= 1f) (t - 0.5f) else 0.5f + (t - 1f) / 2f
private fun knobToTempo(v: Float): Float {
    val t = if (v <= 0.5f) 0.5f + v else 1f + (v - 0.5f) * 2f
    val snapped = (t * 100).roundToInt() / 100f
    return if (abs(snapped - 1f) < 0.03f) 1f else snapped
}
private fun stepTempo(t: Float, dir: Int) =
    (((t * 20).roundToInt() + dir) / 20f).coerceIn(FxSettings.MIN_TEMPO, FxSettings.MAX_TEMPO)

/** System media volume, kept in sync with the hardware buttons. */
@Composable
private fun VolumeKnob() {
    val context = LocalContext.current
    val am = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var level by remember { mutableIntStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var knob by remember { mutableFloatStateOf(level.toFloat() / max) }

    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (v != level) {
                    level = v
                    knob = v.toFloat() / max
                }
            }
        }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    KnobBelow("Volume", "${(knob * 100).roundToInt()}%", knob, 176.dp, color = Sky) { v ->
        knob = v
        val target = (v * max).roundToInt()
        if (target != level) {
            level = target
            runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        }
    }
}
