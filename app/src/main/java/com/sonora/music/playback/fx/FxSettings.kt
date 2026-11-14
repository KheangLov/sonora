package com.sonora.music.playback.fx

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything the audio engine needs to know. Immutable, so the audio thread can grab a
 * consistent snapshot with a single read and only rebuild filters when the reference changes.
 */
@Immutable
data class FxSettings(
    // Equalizer
    val eqEnabled: Boolean = true,
    val preamp: Float = 0f,                                   // dB, ±MAX_DB
    val bands: List<Float> = List(BAND_FREQS.size) { 0f },   // dB per band, ±MAX_DB
    val presetName: String = "Flat",
    // Tone
    val toneEnabled: Boolean = true,
    val bass: Float = 0f,                                     // 0..1
    val treble: Float = 0f,                                   // 0..1
    // Output
    val limiterEnabled: Boolean = true,
    val balance: Float = 0f,                                  // -1 (left) .. 1 (right)
    val stereoExpand: Float = 0f,                             // 0..1
    val mono: Boolean = false,
    val tempo: Float = 1f,                                    // 0.5..2.0
) {
    companion object {
        val BAND_FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        const val MAX_DB = 15f
        const val TONE_MAX_DB = 12f
        const val BASS_HZ = 110f
        const val TREBLE_HZ = 9000f
        const val BAND_Q = 1.41f      // ~1 octave per band
        const val CUSTOM = "Custom"
        const val MIN_TEMPO = 0.5f
        const val MAX_TEMPO = 2.0f
    }
}

fun bandLabel(hz: Float): String = if (hz >= 1000f) "${(hz / 1000f).toInt()}K" else hz.toInt().toString()

@Immutable
data class EqPreset(val name: String, val preamp: Float, val bands: List<Float>, val builtIn: Boolean = true)

private fun p(name: String, preamp: Float, vararg b: Float) = EqPreset(name, preamp, b.toList())

val BuiltInPresets: List<EqPreset> = listOf(
    p("Flat", 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    p("Bass Boost", -3f, 7f, 6f, 4.5f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
    p("Bass Reducer", 0f, -6f, -5f, -3.5f, -1.5f, 0f, 0f, 0f, 0f, 0f, 0f),
    p("Treble Boost", -2f, 0f, 0f, 0f, 0f, 0f, 0f, 1.5f, 3.5f, 5.5f, 6.5f),
    p("Vocal", -1f, -2f, -2f, -1f, 1f, 3f, 3.5f, 3f, 1.5f, 0f, -1f),
    p("Pop", -1f, -1f, 0f, 2f, 3.5f, 4f, 2.5f, 0.5f, 0f, 0.5f, 1f),
    p("Rock", -2f, 5f, 4f, 2.5f, 0f, -1.5f, -1f, 1f, 3f, 4f, 4.5f),
    p("Electronic", -3f, 5.5f, 5f, 2f, 0f, -2f, 1.5f, 0.5f, 1.5f, 4.5f, 5f),
    p("Hip-Hop", -3f, 6f, 5.5f, 3f, 1.5f, -1f, -1f, 1f, 0f, 2f, 3f),
    p("Jazz", -1f, 3.5f, 2.5f, 1f, 2f, -1.5f, -1.5f, 0f, 1.5f, 3f, 3.5f),
    p("Classical", -1f, 4f, 3f, 2.5f, 2f, -1f, -1f, 0f, 2f, 3f, 3.5f),
    p("Acoustic", -1f, 4f, 3.5f, 2.5f, 1f, 1.5f, 1.5f, 2.5f, 3f, 2.5f, 1.5f),
    p("Loudness", -4f, 6f, 4f, 0f, 0f, -2f, 0f, -1f, -4f, 5f, 1f),
    p("Late Night", 0f, 3f, 2.5f, 1.5f, 0f, -1f, 0f, 0.5f, 1f, 0f, -1.5f),
    p("Small Speakers", -3f, 0f, 0f, 5f, 4f, 2.5f, 1f, 0f, 1.5f, 2.5f, 0f),
    p("Headphones", -2f, 4.5f, 6f, 3f, -1.5f, -1f, 1f, 2.5f, 5f, 6.5f, 7f),
)

/** Normalized biquad (a0 = 1), RBJ Audio EQ Cookbook formulas. Shared by the audio thread and the UI graph. */
class Biquad(
    @JvmField val b0: Float, @JvmField val b1: Float, @JvmField val b2: Float,
    @JvmField val a1: Float, @JvmField val a2: Float,
) {
    /** Magnitude response in dB at [hz]. */
    fun magnitudeDb(hz: Double, fs: Double): Double {
        val w = 2 * PI * hz / fs
        val c1 = cos(w); val s1 = sin(w)
        val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
        val dr = 1 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
        val mag2 = (nr * nr + ni * ni) / (dr * dr + di * di)
        return 10 * log10(mag2.coerceAtLeast(1e-12))
    }

    companion object {
        private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat())

        private fun w0(hz: Float, fs: Int) = 2 * PI * hz.coerceAtMost(fs * 0.45f) / fs

        fun peaking(hz: Float, q: Float, db: Float, fs: Int): Biquad {
            val a = 10.0.pow(db / 40.0)
            val w = w0(hz, fs); val cw = cos(w); val alpha = sin(w) / (2 * q)
            return make(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
        }

        fun lowShelf(hz: Float, db: Float, fs: Int): Biquad {
            val a = 10.0.pow(db / 40.0)
            val w = w0(hz, fs); val cw = cos(w); val alpha = sin(w) / 2 * sqrt(2.0)
            val sa = 2 * sqrt(a) * alpha
            return make(
                a * ((a + 1) - (a - 1) * cw + sa),
                2 * a * ((a - 1) - (a + 1) * cw),
                a * ((a + 1) - (a - 1) * cw - sa),
                (a + 1) + (a - 1) * cw + sa,
                -2 * ((a - 1) + (a + 1) * cw),
                (a + 1) + (a - 1) * cw - sa,
            )
        }

        fun highShelf(hz: Float, db: Float, fs: Int): Biquad {
            val a = 10.0.pow(db / 40.0)
            val w = w0(hz, fs); val cw = cos(w); val alpha = sin(w) / 2 * sqrt(2.0)
            val sa = 2 * sqrt(a) * alpha
            return make(
                a * ((a + 1) + (a - 1) * cw + sa),
                -2 * a * ((a - 1) + (a + 1) * cw),
                a * ((a + 1) + (a - 1) * cw - sa),
                (a + 1) - (a - 1) * cw + sa,
                2 * ((a - 1) - (a + 1) * cw),
                (a + 1) - (a - 1) * cw - sa,
            )
        }

        /** Band-pass with 0 dB peak gain — used by the spectrum analyzer. */
        fun bandPass(hz: Float, q: Float, fs: Int): Biquad {
            val w = w0(hz, fs); val cw = cos(w); val alpha = sin(w) / (2 * q)
            return make(alpha, 0.0, -alpha, 1 + alpha, -2 * cw, 1 - alpha)
        }
    }
}

/** Turns settings into the list of filters to run. Filters that would do nothing are skipped. */
object FxChain {
    fun filters(s: FxSettings, fs: Int): List<Biquad> {
        val out = ArrayList<Biquad>(12)
        if (s.eqEnabled) {
            s.bands.forEachIndexed { i, db ->
                if (kotlin.math.abs(db) >= 0.05f) out += Biquad.peaking(FxSettings.BAND_FREQS[i], FxSettings.BAND_Q, db, fs)
            }
        }
        if (s.toneEnabled) {
            if (s.bass > 0.005f) out += Biquad.lowShelf(FxSettings.BASS_HZ, s.bass * FxSettings.TONE_MAX_DB, fs)
            if (s.treble > 0.005f) out += Biquad.highShelf(FxSettings.TREBLE_HZ, s.treble * FxSettings.TONE_MAX_DB, fs)
        }
        return out
    }

    fun preampDb(s: FxSettings): Float = if (s.eqEnabled) s.preamp else 0f

    /** Total response (dB) at the given frequencies, for drawing the curve. */
    fun response(s: FxSettings, freqs: DoubleArray, fs: Int = 48_000): FloatArray {
        val filters = filters(s, fs)
        val pre = preampDb(s)
        return FloatArray(freqs.size) { i ->
            var db = pre.toDouble()
            for (f in filters) db += f.magnitudeDb(freqs[i], fs.toDouble())
            db.toFloat()
        }
    }
}
