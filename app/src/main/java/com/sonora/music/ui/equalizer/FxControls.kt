package com.sonora.music.ui.equalizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sonora.music.playback.fx.AudioFx
import com.sonora.music.playback.fx.FxAudioProcessor
import com.sonora.music.playback.fx.FxChain
import com.sonora.music.playback.fx.FxSettings
import com.sonora.music.ui.theme.Accent
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

internal val Teal = Color(0xFF5EEAD4)
internal val Amber = Color(0xFFFFC53D)
internal val Sky = Color(0xFF60A5FA)
internal val KnobFace = Color(0xFF1E1E23)
internal val Muted = Color(0xFF71717A)

/** Poweramp-style "heat": cool when subtle, lime in the middle, amber when pushed hard. */
internal fun heatColor(t: Float): Color {
    val x = t.coerceIn(0f, 1f)
    return if (x < 0.5f) lerp(Teal, Accent, x / 0.5f) else lerp(Accent, Amber, (x - 0.5f) / 0.5f)
}

// ------------------------------------------------------------------ vertical slider

/**
 * Glowing vertical fader. Drag to move (relative, so it never jumps), double-tap to zero.
 * A light haptic tick fires when crossing 0 dB.
 */
@Composable
fun FxSlider(
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    step: Float = 0.1f,
) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val thumbW = 26.dp
    val thumbH = 52.dp
    val alpha = if (enabled) 1f else 0.45f

    Box(
        modifier
            .pointerInput(min, max) {
                detectTapGestures(onDoubleTap = { change(0f.coerceIn(min, max)) })
            }
            .pointerInput(min, max, step) {
                var acc = 0f
                val pad = thumbH.toPx() / 2
                detectVerticalDragGestures(
                    onDragStart = { acc = current },
                    onVerticalDrag = { c, dy ->
                        c.consume()
                        val track = (size.height - 2 * pad).coerceAtLeast(1f)
                        val before = acc
                        acc = (acc - dy / track * (max - min)).coerceIn(min, max)
                        val snapped = (acc / step).roundToInt() * step
                        if ((before < 0f) != (acc < 0f) && min < 0f) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        change(snapped)
                    },
                )
            }
            .drawBehind {
                val pad = thumbH.toPx() / 2
                val cx = size.width / 2
                val top = pad
                val bottom = size.height - pad
                val t = ((current - min) / (max - min)).coerceIn(0f, 1f)
                val y = bottom - t * (bottom - top)

                // ticks: top / center / bottom
                val tickW = 5.dp.toPx()
                val gap = 9.dp.toPx()
                for (ty in listOf(top, (top + bottom) / 2, bottom)) {
                    drawLine(Muted.copy(alpha = 0.6f * alpha), Offset(cx - gap - tickW, ty), Offset(cx - gap, ty), 1.dp.toPx())
                    drawLine(Muted.copy(alpha = 0.6f * alpha), Offset(cx + gap, ty), Offset(cx + gap + tickW, ty), 1.dp.toPx())
                }
                // track
                drawLine(Color.White.copy(alpha = 0.16f * alpha), Offset(cx, top), Offset(cx, bottom), 2.dp.toPx(), StrokeCap.Round)
                // glowing fill from the bottom up to the thumb
                val glow = heatColor(t)
                val brush = Brush.verticalGradient(listOf(glow, Teal.copy(alpha = 0.9f)), startY = y, endY = bottom)
                drawLine(brush, Offset(cx, y), Offset(cx, bottom), 8.dp.toPx(), StrokeCap.Round, alpha = 0.22f * alpha)
                drawLine(brush, Offset(cx, y), Offset(cx, bottom), 3.dp.toPx(), StrokeCap.Round, alpha = alpha)
                // thumb
                val tw = thumbW.toPx().coerceAtMost(size.width - 2.dp.toPx())
                val th = thumbH.toPx()
                val tl = Offset(cx - tw / 2, y - th / 2)
                drawRoundRect(Color.Black.copy(alpha = 0.5f), tl + Offset(0f, 3.dp.toPx()), Size(tw, th), CornerRadius(tw / 2))
                drawRoundRect(lerp(KnobFace, Color.Black, 0.1f), tl, Size(tw, th), CornerRadius(tw / 2))
                drawRoundRect(Color.White.copy(alpha = 0.12f), tl, Size(tw, th), CornerRadius(tw / 2), style = Stroke(1.dp.toPx()))
                drawLine(Color.White.copy(alpha = 0.9f * alpha), Offset(cx - tw * 0.3f, y), Offset(cx + tw * 0.3f, y), 3.dp.toPx(), StrokeCap.Round)
            }
    )
}

// ------------------------------------------------------------------ rotary knob

private const val START_DEG = 135f
private const val SWEEP_DEG = 270f

/**
 * Rotary knob with a glowing arc. Turn it with a circular drag; double-tap to reset.
 * [value] is normalized 0..1. [bipolar] draws the arc from 12 o'clock (for balance / tempo).
 */
@Composable
fun FxKnob(
    value: Float,
    onChange: (Float) -> Unit,
    size: Dp,
    modifier: Modifier = Modifier,
    bipolar: Boolean = false,
    color: Color = heatColor(value),
    enabled: Boolean = true,
    resetTo: Float = if (bipolar) 0.5f else 0f,
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val haptics = LocalHapticFeedback.current
    val alpha = if (enabled) 1f else 0.45f

    Canvas(
        modifier
            .size(size)
            .pointerInput(resetTo) { detectTapGestures(onDoubleTap = { change(resetTo) }) }
            .pointerInput(bipolar) {
                var acc = 0f
                var lastAngle = 0f
                detectDragGestures(
                    onDragStart = { p ->
                        acc = current
                        lastAngle = angleOf(p, this.size.width / 2f, this.size.height / 2f)
                    },
                    onDrag = { c, _ ->
                        c.consume()
                        val a = angleOf(c.position, this.size.width / 2f, this.size.height / 2f)
                        var d = a - lastAngle
                        if (d > 180f) d -= 360f
                        if (d < -180f) d += 360f
                        lastAngle = a
                        val before = acc
                        acc = (acc + d / SWEEP_DEG).coerceIn(0f, 1f)
                        if (bipolar && (before < 0.5f) != (acc < 0.5f)) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        change(acc)
                    },
                )
            }
    ) {
        val stroke = 4.dp.toPx()
        val ring = this.size.minDimension / 2 - 8.dp.toPx()
        val face = ring - 7.dp.toPx()
        val c = center

        // arc track (subtle) and value arc with glow
        val arcTopLeft = Offset(c.x - ring, c.y - ring)
        val arcSize = Size(ring * 2, ring * 2)
        drawArc(Color.White.copy(alpha = 0.05f), START_DEG, SWEEP_DEG, false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        val (from, sweep) = if (bipolar) 270f to (value - 0.5f) * SWEEP_DEG else START_DEG to value * SWEEP_DEG
        if (kotlin.math.abs(sweep) > 0.5f) {
            drawArc(color.copy(alpha = 0.22f * alpha), from, sweep, false, arcTopLeft, arcSize, style = Stroke(stroke * 3f, cap = StrokeCap.Round))
            drawArc(color.copy(alpha = alpha), from, sweep, false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }

        // face
        drawCircle(Color.Black.copy(alpha = 0.6f), face + 2.dp.toPx(), c + Offset(0f, 3.dp.toPx()))
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFF2A2A30), KnobFace), center = c - Offset(face * 0.3f, face * 0.4f), radius = face * 1.6f),
            face, c,
        )
        drawCircle(Color.White.copy(alpha = 0.07f), face, c, style = Stroke(1.dp.toPx()))

        // indicator notch
        val ang = Math.toRadians((START_DEG + value * SWEEP_DEG).toDouble())
        val r1 = face * 0.72f
        val r2 = face * 0.86f
        drawLine(
            Color.White.copy(alpha = 0.9f * alpha),
            Offset(c.x + r1 * cos(ang).toFloat(), c.y + r1 * sin(ang).toFloat()),
            Offset(c.x + r2 * cos(ang).toFloat(), c.y + r2 * sin(ang).toFloat()),
            5.dp.toPx(), StrokeCap.Round,
        )
    }
}

private fun angleOf(p: Offset, cx: Float, cy: Float): Float =
    Math.toDegrees(atan2((p.y - cy).toDouble(), (p.x - cx).toDouble())).toFloat()

// ------------------------------------------------------------------ pill toggle / button

@Composable
fun FxPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, outlined: Boolean = !active) {
    val shape = CircleShape
    Box(
        modifier
            .clip(shape)
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else if (!outlined) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .then(if (outlined) Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

// ------------------------------------------------------------------ response curve + live spectrum

private const val F_MIN = 20.0
private const val F_MAX = 20000.0
private val GraphFreqs = DoubleArray(120) { i -> F_MIN * (F_MAX / F_MIN).pow(i / 119.0) }

private fun xOf(hz: Double, w: Float): Float = (ln(hz / F_MIN) / ln(F_MAX / F_MIN)).toFloat() * w

/** Frequency-response curve of the current settings over a live spectrum analyzer. */
@Composable
fun ResponseGraph(settings: FxSettings, modifier: Modifier = Modifier) {
    val curve = remember(settings) { FxChain.response(settings, GraphFreqs) }
    val bars = remember { FloatArray(AudioFx.SPECTRUM_BARS) }
    var tick by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        AudioFx.analyzerActive = true
        onDispose { AudioFx.analyzerActive = false }
    }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { now ->
                val fresh = now - AudioFx.spectrumAtNanos < 250_000_000L
                val src = AudioFx.spectrum
                for (i in bars.indices) {
                    val target = if (fresh) src[i] else 0f
                    bars[i] = if (target > bars[i]) bars[i] + (target - bars[i]) * 0.6f else bars[i] * 0.9f
                }
                tick = now
            }
        }
    }

    Canvas(modifier) {
        if (tick < 0L) return@Canvas // reading tick redraws every frame, in the draw phase only
        val w = size.width
        val h = size.height
        val mid = h / 2

        // spectrum: rounded pills mirrored around the center line
        val slot = w / AudioFx.SPECTRUM_BARS
        val pill = (slot * 0.62f).coerceAtMost(14.dp.toPx())
        for (i in bars.indices) {
            val x = xOf(FxAudioProcessor.spectrumFreq(i).toDouble(), w).coerceIn(slot / 2, w - slot / 2)
            val half = (pill / 2) + bars[i] * (h * 0.42f)
            drawRoundRect(
                Color.White.copy(alpha = 0.10f + 0.55f * bars[i]),
                Offset(x - pill / 2, mid - half),
                Size(pill, half * 2),
                CornerRadius(pill / 2),
            )
        }

        // 0 dB line
        drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, mid), Offset(w, mid), 1.dp.toPx())

        // response curve (±MAX_DB fills the height)
        val scale = (h / 2 - 4.dp.toPx()) / FxSettings.MAX_DB
        val line = Path()
        val fill = Path()
        curve.forEachIndexed { i, db ->
            val x = xOf(GraphFreqs[i], w)
            val y = (mid - db.coerceIn(-FxSettings.MAX_DB, FxSettings.MAX_DB) * scale)
            if (i == 0) { line.moveTo(x, y); fill.moveTo(x, mid); fill.lineTo(x, y) } else { line.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(w, mid); fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(Accent.copy(alpha = 0.28f), Accent.copy(alpha = 0.02f), Accent.copy(alpha = 0.28f))))
        drawPath(line, Accent.copy(alpha = 0.3f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
        drawPath(line, Accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Tiny static curve used in the preset list. */
@Composable
fun PresetThumb(bands: List<Float>, preamp: Float, modifier: Modifier = Modifier, color: Color = Accent) {
    val curve = remember(bands, preamp) { FxChain.response(FxSettings(bands = bands, preamp = preamp, toneEnabled = false), GraphFreqs) }
    Canvas(modifier) { drawCurve(curve, color) }
}

private fun DrawScope.drawCurve(curve: FloatArray, color: Color) {
    val mid = size.height / 2
    val scale = (size.height / 2) / FxSettings.MAX_DB
    drawLine(Color.White.copy(alpha = 0.1f), Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
    val path = Path()
    curve.forEachIndexed { i, db ->
        val x = xOf(GraphFreqs[i], size.width)
        val y = mid - db.coerceIn(-FxSettings.MAX_DB, FxSettings.MAX_DB) * scale
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
}
