package com.sonora.music.playback.fx

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Sonora's own DSP, Poweramp-style: it runs inside ExoPlayer's audio pipeline, so it works the
 * same on every phone and every output (speaker, wired, Bluetooth) without relying on the
 * device's built-in android.media.audiofx effects.
 *
 * Chain per frame: preamp → 10-band peaking EQ → bass/treble shelves → stereo width / mono →
 * balance → peak limiter. Neutral settings bypass the work entirely.
 */
@OptIn(UnstableApi::class)
class FxAudioProcessor : BaseAudioProcessor() {

    private var channels = 0
    private var sampleRate = 0
    private var isFloat = false

    private var applied: FxSettings? = null
    private var filters: Array<Biquad> = emptyArray()
    private var z1 = FloatArray(0)
    private var z2 = FloatArray(0)

    private var preTarget = 1f
    private var pre = 1f
    private var smoothK = 0.002f
    private var balL = 1f
    private var balR = 1f
    private var width = 1f
    private var limiter = true
    private var limEnv = 1f
    private var limRelease = 0.0005f
    private var bypass = true

    private val frame = FloatArray(8)

    // Spectrum analyzer (mono mix, post-processing)
    private var bp: Array<Biquad> = emptyArray()
    private val bpZ1 = FloatArray(AudioFx.SPECTRUM_BARS)
    private val bpZ2 = FloatArray(AudioFx.SPECTRUM_BARS)
    private val bpAcc = FloatArray(AudioFx.SPECTRUM_BARS)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val ok = (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) &&
            inputAudioFormat.channelCount in 1..8
        // Unsupported formats simply pass through untouched (processor inactive).
        return if (ok) inputAudioFormat else AudioFormat.NOT_SET
    }

    override fun onFlush() {
        val f = inputAudioFormat
        if (f.sampleRate != sampleRate || f.channelCount != channels) {
            sampleRate = f.sampleRate
            channels = f.channelCount
            bp = Array(AudioFx.SPECTRUM_BARS) { i -> Biquad.bandPass(spectrumFreq(i), 4f, sampleRate) }
        }
        isFloat = f.encoding == C.ENCODING_PCM_FLOAT
        smoothK = 1f - exp(-1.0 / (0.012 * sampleRate.coerceAtLeast(1))).toFloat()
        limRelease = 1f - exp(-1.0 / (0.120 * sampleRate.coerceAtLeast(1))).toFloat()
        applied = null
        limEnv = 1f
        bpZ1.fill(0f); bpZ2.fill(0f)
    }

    override fun onReset() {
        applied = null
        filters = emptyArray()
        z1 = FloatArray(0); z2 = FloatArray(0)
        channels = 0; sampleRate = 0
    }

    private fun rebuild(s: FxSettings) {
        val newFilters = FxChain.filters(s, sampleRate).toTypedArray()
        if (z1.size != newFilters.size * channels || newFilters.size != filters.size) {
            // Filter set changed shape: start from silence state (inaudible at this point).
            z1 = FloatArray(newFilters.size * channels)
            z2 = FloatArray(newFilters.size * channels)
        }
        filters = newFilters
        preTarget = 10f.pow(FxChain.preampDb(s) / 20f)
        balL = if (s.balance > 0f) 1f - s.balance else 1f
        balR = if (s.balance < 0f) 1f + s.balance else 1f
        width = if (s.mono) 0f else 1f + s.stereoExpand * 1.5f
        limiter = s.limiterEnabled
        bypass = filters.isEmpty() && preTarget == 1f && balL == 1f && balR == 1f && width == 1f
        applied = s
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val s = AudioFx.settings.value
        if (s !== applied) rebuild(s)
        val analyze = AudioFx.analyzerActive
        val out = replaceOutputBuffer(size)

        if (bypass && abs(pre - 1f) < 1e-4f && !analyze) {
            pre = 1f
            out.put(inputBuffer)
            out.flip()
            return
        }

        val ch = channels
        val bytesPerSample = if (isFloat) 4 else 2
        val frames = size / (bytesPerSample * ch)
        val nf = filters.size
        if (analyze) bpAcc.fill(0f)

        for (n in 0 until frames) {
            // read
            for (c in 0 until ch) {
                frame[c] = if (isFloat) inputBuffer.float else inputBuffer.short / 32768f
            }
            if (!bypass || pre != 1f) {
                pre += (preTarget - pre) * smoothK
                // preamp + EQ/tone per channel (transposed direct form II)
                for (c in 0 until ch) {
                    var x = frame[c] * pre
                    var k = c
                    for (f in 0 until nf) {
                        val q = filters[f]
                        val y = q.b0 * x + z1[k]
                        z1[k] = q.b1 * x - q.a1 * y + z2[k]
                        z2[k] = q.b2 * x - q.a2 * y
                        x = y
                        k += ch
                    }
                    frame[c] = x
                }
                if (ch == 2) {
                    if (width != 1f) {
                        val mid = (frame[0] + frame[1]) * 0.5f
                        val side = (frame[0] - frame[1]) * 0.5f * width
                        frame[0] = mid + side
                        frame[1] = mid - side
                    }
                    frame[0] *= balL
                    frame[1] *= balR
                }
                if (limiter) {
                    var peak = 0f
                    for (c in 0 until ch) peak = maxOf(peak, abs(frame[c]))
                    val target = if (peak > LIMIT) LIMIT / peak else 1f
                    limEnv = if (target < limEnv) target else limEnv + (target - limEnv) * limRelease
                    if (limEnv < 1f) for (c in 0 until ch) frame[c] *= limEnv
                }
            }
            if (analyze) {
                var m = 0f
                for (c in 0 until ch) m += frame[c]
                m /= ch
                for (b in bp.indices) {
                    val q = bp[b]
                    val y = q.b0 * m + bpZ1[b]
                    bpZ1[b] = q.b1 * m - q.a1 * y + bpZ2[b]
                    bpZ2[b] = q.b2 * m - q.a2 * y
                    bpAcc[b] += y * y
                }
            }
            // write
            for (c in 0 until ch) {
                val v = frame[c].coerceIn(-1f, 1f)
                if (isFloat) out.putFloat(v) else out.putShort((v * 32767f).roundToInt().toShort())
            }
        }
        // Skip any trailing partial frame so the input is fully consumed.
        inputBuffer.position(inputBuffer.limit())
        out.flip()

        if (analyze && frames > 0) {
            val levels = FloatArray(bpAcc.size) { b ->
                val rms = sqrt(bpAcc[b] / frames)
                val db = 20f * log10(rms + 1e-9f)
                ((db + 60f) / 54f).coerceIn(0f, 1f)
            }
            AudioFx.spectrum = levels
            AudioFx.spectrumAtNanos = System.nanoTime()
        }
    }

    companion object {
        private const val LIMIT = 0.966f // ≈ -0.3 dBFS

        /** Log-spaced analyzer bands, 40 Hz .. 16 kHz. */
        fun spectrumFreq(i: Int): Float {
            val t = i.toFloat() / (AudioFx.SPECTRUM_BARS - 1)
            return (40.0 * (16000.0 / 40.0).pow(t.toDouble())).toFloat()
        }
    }
}
