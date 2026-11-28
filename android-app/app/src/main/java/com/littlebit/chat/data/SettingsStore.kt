package com.littlebit.chat.data

import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import android.content.Context
import android.content.SharedPreferences
import com.littlebit.chat.engine.ChatTemplate
import com.littlebit.chat.engine.SamplerConfig
import kotlin.math.roundToInt

/**
 * App settings backed by SharedPreferences. Inference defaults follow the
 * Realme P3 Ultra tuning: 4 threads (Big.LITTLE performance cores), 2048 ctx,
 * repeat penalty 1.18, DRY 0.8.
 */
class SettingsStore(context: Context) {

    companion object {
        /**
         * Calm, accurate offline clause. The old text ("You cannot search the web
         * or check live weather or news") made small 1B models refuse ordinary
         * static questions such as "what is the highest building?".
         */
        const val OFFLINE =
            "Answer general knowledge, coding, math, and creative questions directly using your training knowledge. " +
                "Only mention that you are offline if the user specifically asks for live, real-time data " +
                "(such as today's weather, live scores, or breaking news)."
        const val DEFAULT_SYSTEM_PROMPT =
            "You are LittleBit Chat, a helpful on-device AI assistant. Be concise and accurate. $OFFLINE"

        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_TOP_K = "top_k"
        private const val KEY_TOP_P_PERCENT = "top_p_percent"
        private const val KEY_MIN_P_PERCENT = "min_p_percent"
        private const val KEY_REPEAT_PENALTY = "repeat_penalty"
        private const val KEY_PENALTY_LAST_N = "penalty_last_n"
        private const val KEY_FREQUENCY_PENALTY = "frequency_penalty"
        private const val KEY_PRESENCE_PENALTY = "presence_penalty"
        private const val KEY_DRY_MULTIPLIER = "dry_multiplier"
        private const val KEY_DRY_BASE = "dry_base"
        private const val KEY_DRY_ALLOWED_LENGTH = "dry_allowed_length"
        private const val KEY_DRY_PENALTY_LAST_N = "dry_penalty_last_n"
        private const val KEY_XTC_PROBABILITY = "xtc_probability"
        private const val KEY_XTC_THRESHOLD = "xtc_threshold"
        private const val KEY_TOP_N_SIGMA = "top_n_sigma"
        private const val KEY_TYPICAL_P = "typical_p"
        private const val KEY_DYNATEMP_RANGE = "dynatemp_range"
        private const val KEY_DYNATEMP_EXPONENT = "dynatemp_exponent"
        private const val KEY_MIROSTAT_ENABLED = "mirostat_enabled"
        private const val KEY_MIROSTAT_TAU = "mirostat_tau"
        private const val KEY_MIROSTAT_ETA = "mirostat_eta"
        private const val KEY_SAMPLER_SEED = "sampler_seed"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("littlebit_settings", Context.MODE_PRIVATE)

    private val samplerState = mutableStateOf(readSamplerConfig())

    /** Complete local sampler suite, stored as one atomic preference update. */
    var samplerConfig: SamplerConfig
        get() = samplerState.value
        set(value) {
            val safe = value.clamped()
            prefs.edit()
                .putSamplerDouble(KEY_TEMPERATURE, safe.temperature)
                .putInt(KEY_TOP_K, safe.topK)
                .putSamplerDouble(KEY_TOP_P_PERCENT, safe.topP)
                .putSamplerDouble(KEY_MIN_P_PERCENT, safe.minP)
                .putSamplerDouble(KEY_REPEAT_PENALTY, safe.repeatPenalty)
                .putInt(KEY_PENALTY_LAST_N, safe.penaltyLastN)
                .putSamplerDouble(KEY_FREQUENCY_PENALTY, safe.frequencyPenalty)
                .putSamplerDouble(KEY_PRESENCE_PENALTY, safe.presencePenalty)
                .putSamplerDouble(KEY_DRY_MULTIPLIER, safe.dryMultiplier)
                .putSamplerDouble(KEY_DRY_BASE, safe.dryBase)
                .putInt(KEY_DRY_ALLOWED_LENGTH, safe.dryAllowedLength)
                .putInt(KEY_DRY_PENALTY_LAST_N, safe.dryPenaltyLastN)
                .putSamplerDouble(KEY_XTC_PROBABILITY, safe.xtcProbability)
                .putSamplerDouble(KEY_XTC_THRESHOLD, safe.xtcThreshold)
                .putSamplerDouble(KEY_TOP_N_SIGMA, safe.topNSigma)
                .putSamplerDouble(KEY_TYPICAL_P, safe.typicalP)
                .putSamplerDouble(KEY_DYNATEMP_RANGE, safe.dynatempRange)
                .putSamplerDouble(KEY_DYNATEMP_EXPONENT, safe.dynatempExponent)
                .putBoolean(KEY_MIROSTAT_ENABLED, safe.mirostatEnabled)
                .putSamplerDouble(KEY_MIROSTAT_TAU, safe.mirostatTau)
                .putSamplerDouble(KEY_MIROSTAT_ETA, safe.mirostatEta)
                .putLong(KEY_SAMPLER_SEED, safe.seed)
                .apply()
            samplerState.value = safe
        }

    private fun SharedPreferences.Editor.putSamplerDouble(
        key: String,
        value: Double,
    ): SharedPreferences.Editor = putLong(key, SamplerPreferenceCodec.encode(value))

    // Shared observable preferences let settings changes reach the open chat immediately.
    private fun <T> preference(key: String, default: T, read: (String, T) -> T,
                               write: (SharedPreferences.Editor, String, T) -> Unit): ReadWriteProperty<Any?, T> {
        val state = mutableStateOf(read(key, default))
        return object : ReadWriteProperty<Any?, T> {
            override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                prefs.edit().also { write(it, key, value) }.apply()
                state.value = value
            }
        }
    }
    private fun flag(key: String, default: Boolean) =
        preference(key, default, prefs::getBoolean) { e, k, v -> e.putBoolean(k, v) }
    private fun number(key: String, default: Int) =
        preference(key, default, prefs::getInt) { e, k, v -> e.putInt(k, v) }
    private fun label(key: String, default: String) =
        preference(key, default, { k, d -> prefs.getString(k, d) ?: d }) { e, k, v -> e.putString(k, v) }

    var unloadPolicy by label("background_unload", "After 1 min")
    var keepAwake by flag("keep_awake", true)
    var notifyGeneration by flag("notify_generation", true)
    var thinkingBudget by label("think_budget", "Unlimited")
    var searchProvider by label("search_provider", "DuckDuckGo")
    var searxUrl by label("searx_url", "")
    var linkedModelNames by label("linked_model_names", "{}")
    var lastDocumentName by label("last_document_name", "")

    /** Chat context meter under the composer. */
    var showContextBar by flag("show_context_bar", true)

    /**
     * Pre-load RAM warning modal. Default ON; power users can bypass the
     * estimate entirely (the benchmark setup dialog stays informational).
     */
    var ramSafetyWarnings by flag("ram_safety_warnings", true)

    /** Image Studio: use a staged taesd.safetensors for 1-3 s VAE decoding. */
    var taesdEnabled by flag("taesd_enabled", false)

    /** Idle auto-unload of the loaded model: "Off", "2 min", "5 min", "10 min". */
    var idleUnload by label("idle_unload", "5 min")
    var captionMode by label("caption_mode", if (prefs.getBoolean("show_performance", true)) "Minimal" else "Off")
    var batch by number("prefill_batch", 512)
    var flash by flag("flash_attention", true)
    var topK: Int
        get() = samplerConfig.topK
        set(value) { samplerConfig = samplerConfig.copy(topK = value) }
    var topPercent: Int
        get() = (samplerConfig.topP * 100.0).roundToInt()
        set(value) { samplerConfig = samplerConfig.copy(topP = value / 100.0) }
    var minPercent: Int
        get() = (samplerConfig.minP * 100.0).roundToInt()
        set(value) { samplerConfig = samplerConfig.copy(minP = value / 100.0) }
    var maxTokens by number("max_tokens", 1024)
    var theme by label("appearance_mode", "System")
    var accent by label("appearance_accent", "Emerald")
    var font by number("chat_font_sp", 15)
    var haptics by flag("chat_haptics", true)
    var calculator by flag("plugin_calc", true)
    var clock by flag("plugin_clock", false)
    var benchmarkHistory by label("benchmarks", "[]")
    var customPrompt by label("custom_prompt", systemPrompt)
    var persona by label("persona", if (systemPrompt == DEFAULT_SYSTEM_PROMPT) "Concise" else "Custom")

    /**
     * On-disk KV cache, on by default. Chats shorter than
     * [com.littlebit.chat.engine.KvCacheStore.MIN_SAVE_TOKENS] are skipped so a
     * short conversation never pays for a cache write it cannot reuse.
     */
    var saveKvCache by flag("save_kv_cache", true)

    /** Standard-edition cloud API (the offline build never reads these for networking). */
    var cloudMode by flag("cloud_mode", false)
    var cloudPreset by label("cloud_preset", "OpenRouter")
    var cloudEndpoint by label("cloud_endpoint", "https://openrouter.ai/api/v1")
    var cloudModel by label("cloud_model", "")

    /** CPU threads 2–8. Default 4 = optimal for Big.LITTLE performance cores. */
    var threads: Int
        get() = prefs.getInt("threads", 4)
        set(v) { prefs.edit().putInt("threads", v.coerceIn(2, 8)).apply() }

    /** True once the user picked a context size (a 6 GB phone keeps the 2K default). */
    val contextSizeChosen: Boolean get() = prefs.contains("context_size")

    /** Context size 1024–8192. Applies on next model load. */
    var contextSize: Int
        get() = prefs.getInt("context_size", 2048)
        set(v) { prefs.edit().putInt("context_size", v.coerceIn(1024, 16384)).apply() }

    var temperature: Double
        get() = samplerConfig.temperature
        set(value) { samplerConfig = samplerConfig.copy(temperature = value) }

    var repeatPenalty: Double
        get() = samplerConfig.repeatPenalty
        set(value) { samplerConfig = samplerConfig.copy(repeatPenalty = value) }

    var dryMultiplier: Double
        get() = samplerConfig.dryMultiplier
        set(value) { samplerConfig = samplerConfig.copy(dryMultiplier = value) }

    var systemPrompt: String
        get() = prefs.getString("system_prompt", DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(v) { prefs.edit().putString("system_prompt", v).apply() }

    /** Selected chat template id; MODEL_DEFAULT keeps the GGUF template. */
    var chatTemplate by label("chat_template", ChatTemplate.MODEL_DEFAULT.id)

    /** User-edited template, only used when chatTemplate is CUSTOM. */
    var customTemplate by label("custom_template", ChatTemplate.DEFAULT_CUSTOM)

    /** Hard stop strings, one per line (max 8, 64 chars each). */
    var stopStrings by label("stop_strings", "")

    /** Instant Reply (background speculative pre-warm). Default OFF. */
    var instantReply: Boolean
        get() = prefs.getBoolean("instant_reply", false)
        set(v) { prefs.edit().putBoolean("instant_reply", v).apply() }

    /** Fast mode appends /no_think (no reasoning chain). Default ON — thinking
     *  doubles wait time and throttles the phone. */
    var fastMode: Boolean
        get() = prefs.getBoolean("fast_mode", true)
        set(v) { prefs.edit().putBoolean("fast_mode", v).apply() }

    /** Last successfully loaded local GGUF for one-tap reload. */
    var lastModelPath: String?
        get() = prefs.getString("last_model_path", null)
        set(v) { prefs.edit().putString("last_model_path", v).apply() }

    /** Vulkan is enabled by default; persist explicit CPU selections. */
    var vulkanOffload: Boolean
        get() = prefs.getBoolean("vulkan_offload", true)
        set(v) { prefs.edit().putBoolean("vulkan_offload", v).apply() }

    /** Requested GPU layers. 99 asks llama.cpp to offload all eligible layers. */
    var vulkanGpuLayers: Int
        get() = prefs.getInt("vulkan_gpu_layers", 99).coerceIn(0, 99)
        set(v) { prefs.edit().putInt("vulkan_gpu_layers", v.coerceIn(0, 99)).apply() }

    private fun readSamplerConfig(): SamplerConfig {
        fun value(key: String, default: Double) = readSamplerDouble(key, default)
        fun percent(key: String, default: Double) = readSamplerPercent(key, default)
        return SamplerConfig(
            temperature = value(KEY_TEMPERATURE, SamplerConfig.DEFAULT_TEMPERATURE),
            topK = prefs.getInt(KEY_TOP_K, SamplerConfig.DEFAULT_TOP_K),
            topP = percent(KEY_TOP_P_PERCENT, SamplerConfig.DEFAULT_TOP_P),
            minP = percent(KEY_MIN_P_PERCENT, SamplerConfig.DEFAULT_MIN_P),
            repeatPenalty = value(KEY_REPEAT_PENALTY, SamplerConfig.DEFAULT_REPEAT_PENALTY),
            penaltyLastN = prefs.getInt(KEY_PENALTY_LAST_N, SamplerConfig.DEFAULT_PENALTY_LAST_N),
            frequencyPenalty = value(KEY_FREQUENCY_PENALTY, SamplerConfig.DEFAULT_FREQUENCY_PENALTY),
            presencePenalty = value(KEY_PRESENCE_PENALTY, SamplerConfig.DEFAULT_PRESENCE_PENALTY),
            dryMultiplier = value(KEY_DRY_MULTIPLIER, SamplerConfig.DEFAULT_DRY_MULTIPLIER),
            dryBase = value(KEY_DRY_BASE, SamplerConfig.DEFAULT_DRY_BASE),
            dryAllowedLength = prefs.getInt(KEY_DRY_ALLOWED_LENGTH, SamplerConfig.DEFAULT_DRY_ALLOWED_LENGTH),
            dryPenaltyLastN = prefs.getInt(KEY_DRY_PENALTY_LAST_N, SamplerConfig.DEFAULT_DRY_PENALTY_LAST_N),
            xtcProbability = value(KEY_XTC_PROBABILITY, SamplerConfig.DEFAULT_XTC_PROBABILITY),
            xtcThreshold = value(KEY_XTC_THRESHOLD, SamplerConfig.DEFAULT_XTC_THRESHOLD),
            topNSigma = value(KEY_TOP_N_SIGMA, SamplerConfig.DEFAULT_TOP_N_SIGMA),
            typicalP = value(KEY_TYPICAL_P, SamplerConfig.DEFAULT_TYPICAL_P),
            dynatempRange = value(KEY_DYNATEMP_RANGE, SamplerConfig.DEFAULT_DYNATEMP_RANGE),
            dynatempExponent = value(KEY_DYNATEMP_EXPONENT, SamplerConfig.DEFAULT_DYNATEMP_EXPONENT),
            mirostatEnabled = prefs.getBoolean(KEY_MIROSTAT_ENABLED, SamplerConfig.DEFAULT_MIROSTAT_ENABLED),
            mirostatTau = value(KEY_MIROSTAT_TAU, SamplerConfig.DEFAULT_MIROSTAT_TAU),
            mirostatEta = value(KEY_MIROSTAT_ETA, SamplerConfig.DEFAULT_MIROSTAT_ETA),
            seed = prefs.getLong(KEY_SAMPLER_SEED, SamplerConfig.DEFAULT_SEED),
        ).clamped()
    }

    /** Reads current raw-bit values while accepting the original float storage. */
    private fun readSamplerDouble(key: String, default: Double): Double =
        SamplerPreferenceCodec.decode(prefs.all[key]) ?: default

    /** Migrates the original integer-percentage keys without rounding current values. */
    private fun readSamplerPercent(key: String, default: Double): Double =
        SamplerPreferenceCodec.decodePercent(prefs.all[key]) ?: default
}
