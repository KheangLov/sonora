package com.sonora.music.playback.fx

import android.content.Context
import android.content.SharedPreferences
import com.sonora.music.AppGraph
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for the equalizer and effects. The UI writes here, the audio
 * processor reads [settings].value on the audio thread, and everything is persisted.
 */
object AudioFx {
    private lateinit var prefs: SharedPreferences

    private val _settings = MutableStateFlow(FxSettings())
    val settings: StateFlow<FxSettings> = _settings.asStateFlow()

    private val _customPresets = MutableStateFlow<List<EqPreset>>(emptyList())
    val customPresets: StateFlow<List<EqPreset>> = _customPresets.asStateFlow()

    // ---- Spectrum analyzer: only computed while the equalizer screen is visible.
    const val SPECTRUM_BARS = 28
    @Volatile var analyzerActive = false
    @Volatile var spectrum: FloatArray = FloatArray(SPECTRUM_BARS)
        internal set
    @Volatile var spectrumAtNanos: Long = 0L
        internal set

    private var saveJob: Job? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("audio_fx", Context.MODE_PRIVATE)
        runCatching { prefs.getString("settings", null)?.let { _settings.value = decode(JSONObject(it)) } }
        runCatching {
            prefs.getString("presets", null)?.let { raw ->
                val arr = JSONArray(raw)
                _customPresets.value = List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    EqPreset(o.getString("name"), o.optDouble("pre", 0.0).toFloat(), floats(o.getJSONArray("bands")), builtIn = false)
                }
            }
        }
    }

    fun update(block: (FxSettings) -> FxSettings) {
        _settings.update(block)
        scheduleSave()
    }

    // ---------------------------------------------------------------- EQ

    fun setBand(index: Int, db: Float) = update { s ->
        val bands = s.bands.toMutableList().also { it[index] = db.coerceIn(-FxSettings.MAX_DB, FxSettings.MAX_DB) }
        s.copy(bands = bands, eqEnabled = true, presetName = FxSettings.CUSTOM)
    }

    fun setPreamp(db: Float) = update { it.copy(preamp = db.coerceIn(-FxSettings.MAX_DB, FxSettings.MAX_DB), eqEnabled = true) }

    fun applyPreset(p: EqPreset) = update { it.copy(preamp = p.preamp, bands = p.bands, presetName = p.name, eqEnabled = true) }

    fun resetEq() = update {
        it.copy(preamp = 0f, bands = List(FxSettings.BAND_FREQS.size) { 0f }, presetName = "Flat", bass = 0f, treble = 0f)
    }

    fun savePreset(name: String) {
        val clean = name.trim().ifEmpty { return }
        val s = _settings.value
        val preset = EqPreset(clean, s.preamp, s.bands, builtIn = false)
        _customPresets.update { list -> list.filterNot { it.name.equals(clean, ignoreCase = true) } + preset }
        update { it.copy(presetName = clean) }
        savePresets()
    }

    fun deletePreset(name: String) {
        _customPresets.update { list -> list.filterNot { it.name == name } }
        savePresets()
    }

    // ---------------------------------------------------------------- Effects

    fun resetEffects() = update { it.copy(balance = 0f, stereoExpand = 0f, mono = false, tempo = 1f) }

    /** True when anything audible is changed — used to tint the entry-point icon. */
    fun isActive(s: FxSettings): Boolean =
        (s.eqEnabled && (s.preamp != 0f || s.bands.any { it != 0f })) ||
            (s.toneEnabled && (s.bass > 0f || s.treble > 0f)) ||
            s.balance != 0f || s.stereoExpand > 0f || s.mono || s.tempo != 1f

    // ---------------------------------------------------------------- persistence

    private fun scheduleSave() {
        if (!::prefs.isInitialized) return
        saveJob?.cancel()
        saveJob = AppGraph.scope.launch {
            delay(400)
            prefs.edit().putString("settings", encode(_settings.value).toString()).apply()
        }
    }

    private fun savePresets() {
        if (!::prefs.isInitialized) return
        val arr = JSONArray()
        _customPresets.value.forEach { p ->
            arr.put(JSONObject().put("name", p.name).put("pre", p.preamp.toDouble()).put("bands", JSONArray(p.bands.map { it.toDouble() })))
        }
        prefs.edit().putString("presets", arr.toString()).apply()
    }

    private fun encode(s: FxSettings) = JSONObject()
        .put("eq", s.eqEnabled)
        .put("pre", s.preamp.toDouble())
        .put("bands", JSONArray(s.bands.map { it.toDouble() }))
        .put("preset", s.presetName)
        .put("tone", s.toneEnabled)
        .put("bass", s.bass.toDouble())
        .put("treble", s.treble.toDouble())
        .put("limit", s.limiterEnabled)
        .put("balance", s.balance.toDouble())
        .put("expand", s.stereoExpand.toDouble())
        .put("mono", s.mono)
        .put("tempo", s.tempo.toDouble())

    private fun decode(o: JSONObject): FxSettings {
        val d = FxSettings()
        val bands = o.optJSONArray("bands")?.let(::floats)?.takeIf { it.size == FxSettings.BAND_FREQS.size } ?: d.bands
        return FxSettings(
            eqEnabled = o.optBoolean("eq", d.eqEnabled),
            preamp = o.optDouble("pre", 0.0).toFloat(),
            bands = bands,
            presetName = o.optString("preset", d.presetName),
            toneEnabled = o.optBoolean("tone", d.toneEnabled),
            bass = o.optDouble("bass", 0.0).toFloat(),
            treble = o.optDouble("treble", 0.0).toFloat(),
            limiterEnabled = o.optBoolean("limit", d.limiterEnabled),
            balance = o.optDouble("balance", 0.0).toFloat(),
            stereoExpand = o.optDouble("expand", 0.0).toFloat(),
            mono = o.optBoolean("mono", false),
            tempo = o.optDouble("tempo", 1.0).toFloat().coerceIn(FxSettings.MIN_TEMPO, FxSettings.MAX_TEMPO),
        )
    }

    private fun floats(arr: JSONArray): List<Float> = List(arr.length()) { arr.getDouble(it).toFloat() }
}
