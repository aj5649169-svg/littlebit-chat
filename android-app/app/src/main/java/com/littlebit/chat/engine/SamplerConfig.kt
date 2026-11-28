package com.littlebit.chat.engine

/**
 * Pure Kotlin model of the sampler values shared by the UI, persistence layer,
 * and JNI engine.
 *
 * The JNI boundary is a fixed numeric ABI. Keep [SLOT_COUNT], every [IDX_*]
 * constant, and the order in [toDoubleArray] identical to `llama_jni.cpp`.
 */
data class SamplerConfig(
    val temperature: Double = DEFAULT_TEMPERATURE,
    val topK: Int = DEFAULT_TOP_K,
    val topP: Double = DEFAULT_TOP_P,
    val minP: Double = DEFAULT_MIN_P,
    val repeatPenalty: Double = DEFAULT_REPEAT_PENALTY,
    val penaltyLastN: Int = DEFAULT_PENALTY_LAST_N,
    val frequencyPenalty: Double = DEFAULT_FREQUENCY_PENALTY,
    val presencePenalty: Double = DEFAULT_PRESENCE_PENALTY,
    val dryMultiplier: Double = DEFAULT_DRY_MULTIPLIER,
    val dryBase: Double = DEFAULT_DRY_BASE,
    val dryAllowedLength: Int = DEFAULT_DRY_ALLOWED_LENGTH,
    val dryPenaltyLastN: Int = DEFAULT_DRY_PENALTY_LAST_N,
    val xtcProbability: Double = DEFAULT_XTC_PROBABILITY,
    val xtcThreshold: Double = DEFAULT_XTC_THRESHOLD,
    val topNSigma: Double = DEFAULT_TOP_N_SIGMA,
    val typicalP: Double = DEFAULT_TYPICAL_P,
    val dynatempRange: Double = DEFAULT_DYNATEMP_RANGE,
    val dynatempExponent: Double = DEFAULT_DYNATEMP_EXPONENT,
    val mirostatEnabled: Boolean = DEFAULT_MIROSTAT_ENABLED,
    val mirostatTau: Double = DEFAULT_MIROSTAT_TAU,
    val mirostatEta: Double = DEFAULT_MIROSTAT_ETA,
    val seed: Long = DEFAULT_SEED,
) {

    /** Returns a value-safe copy suitable for persistence or JNI serialization. */
    fun clamped(): SamplerConfig = SamplerConfig(
        temperature = temperature.finiteOr(DEFAULT_TEMPERATURE).coerceIn(0.0, 2.0),
        topK = topK.coerceIn(0, 1_000_000),
        topP = topP.finiteOr(DEFAULT_TOP_P).coerceIn(0.0, 1.0),
        minP = minP.finiteOr(DEFAULT_MIN_P).coerceIn(0.0, 1.0),
        repeatPenalty = repeatPenalty.finiteOr(DEFAULT_REPEAT_PENALTY).coerceIn(1.0, 2.0),
        penaltyLastN = penaltyLastN.coerceIn(0, 1_000_000),
        frequencyPenalty = frequencyPenalty.finiteOr(DEFAULT_FREQUENCY_PENALTY).coerceIn(-2.0, 2.0),
        presencePenalty = presencePenalty.finiteOr(DEFAULT_PRESENCE_PENALTY).coerceIn(-2.0, 2.0),
        dryMultiplier = dryMultiplier.finiteOr(DEFAULT_DRY_MULTIPLIER).coerceIn(0.0, 2.0),
        dryBase = dryBase.finiteOr(DEFAULT_DRY_BASE).coerceIn(1.0, 4.0),
        dryAllowedLength = dryAllowedLength.coerceIn(0, 128),
        dryPenaltyLastN = dryPenaltyLastN.coerceIn(0, 1_000_000),
        xtcProbability = xtcProbability.finiteOr(DEFAULT_XTC_PROBABILITY).coerceIn(0.0, 1.0),
        xtcThreshold = xtcThreshold.finiteOr(DEFAULT_XTC_THRESHOLD).coerceIn(0.0, 0.5),
        topNSigma = topNSigma.finiteOr(DEFAULT_TOP_N_SIGMA).coerceIn(0.0, 100.0),
        typicalP = typicalP.finiteOr(DEFAULT_TYPICAL_P).coerceIn(0.0, 1.0),
        dynatempRange = dynatempRange.finiteOr(DEFAULT_DYNATEMP_RANGE).coerceIn(0.0, 4.0),
        dynatempExponent = dynatempExponent.finiteOr(DEFAULT_DYNATEMP_EXPONENT).coerceIn(0.01, 4.0),
        mirostatEnabled = mirostatEnabled,
        mirostatTau = mirostatTau.finiteOr(DEFAULT_MIROSTAT_TAU).coerceIn(0.01, 10.0),
        mirostatEta = mirostatEta.finiteOr(DEFAULT_MIROSTAT_ETA).coerceIn(0.001, 1.0),
        seed = seed.coerceIn(-1L, MAX_SEED),
    )

    /**
     * Serializes the fixed sampler ABI.
     *
     * Slot map (the C++ implementation uses the same order):
     *  0 temperature, 1 top-k, 2 top-p, 3 min-p, 4 repeat penalty,
     *  5 penalty window, 6 frequency penalty, 7 presence penalty,
     *  8 DRY multiplier, 9 DRY base, 10 DRY allowed length,
     * 11 DRY window, 12 XTC probability, 13 XTC threshold,
     * 14 top-n-sigma, 15 typical-p, 16 DynaTemp range,
     * 17 DynaTemp exponent, 18 Mirostat enabled, 19 Mirostat tau,
     * 20 Mirostat eta, 21 seed.
     */
    fun toDoubleArray(): DoubleArray {
        val safe = clamped()
        return doubleArrayOf(
            safe.temperature,
            safe.topK.toDouble(),
            safe.topP,
            safe.minP,
            safe.repeatPenalty,
            safe.penaltyLastN.toDouble(),
            safe.frequencyPenalty,
            safe.presencePenalty,
            safe.dryMultiplier,
            safe.dryBase,
            safe.dryAllowedLength.toDouble(),
            safe.dryPenaltyLastN.toDouble(),
            safe.xtcProbability,
            safe.xtcThreshold,
            safe.topNSigma,
            safe.typicalP,
            safe.dynatempRange,
            safe.dynatempExponent,
            if (safe.mirostatEnabled) 1.0 else 0.0,
            safe.mirostatTau,
            safe.mirostatEta,
            safe.seed.toDouble(),
        )
    }

    companion object {
        const val SLOT_COUNT = 22

        const val IDX_TEMPERATURE = 0
        const val IDX_TOP_K = 1
        const val IDX_TOP_P = 2
        const val IDX_MIN_P = 3
        const val IDX_REPEAT_PENALTY = 4
        const val IDX_PENALTY_LAST_N = 5
        const val IDX_FREQUENCY_PENALTY = 6
        const val IDX_PRESENCE_PENALTY = 7
        const val IDX_DRY_MULTIPLIER = 8
        const val IDX_DRY_BASE = 9
        const val IDX_DRY_ALLOWED_LENGTH = 10
        const val IDX_DRY_PENALTY_LAST_N = 11
        const val IDX_XTC_PROBABILITY = 12
        const val IDX_XTC_THRESHOLD = 13
        const val IDX_TOP_N_SIGMA = 14
        const val IDX_TYPICAL_P = 15
        const val IDX_DYNATEMP_RANGE = 16
        const val IDX_DYNATEMP_EXPONENT = 17
        const val IDX_MIROSTAT_ENABLED = 18
        const val IDX_MIROSTAT_TAU = 19
        const val IDX_MIROSTAT_ETA = 20
        const val IDX_SEED = 21

        const val DEFAULT_TEMPERATURE = 0.7
        const val DEFAULT_TOP_K = 40
        const val DEFAULT_TOP_P = 0.90
        const val DEFAULT_MIN_P = 0.05
        const val DEFAULT_REPEAT_PENALTY = 1.18
        const val DEFAULT_PENALTY_LAST_N = 64
        const val DEFAULT_FREQUENCY_PENALTY = 0.0
        const val DEFAULT_PRESENCE_PENALTY = 0.0
        const val DEFAULT_DRY_MULTIPLIER = 0.8
        const val DEFAULT_DRY_BASE = 1.75
        const val DEFAULT_DRY_ALLOWED_LENGTH = 2
        const val DEFAULT_DRY_PENALTY_LAST_N = 64
        const val DEFAULT_XTC_PROBABILITY = 0.0
        const val DEFAULT_XTC_THRESHOLD = 0.10
        const val DEFAULT_TOP_N_SIGMA = 0.0
        const val DEFAULT_TYPICAL_P = 1.0
        const val DEFAULT_DYNATEMP_RANGE = 0.0
        const val DEFAULT_DYNATEMP_EXPONENT = 1.0
        const val DEFAULT_MIROSTAT_ENABLED = false
        const val DEFAULT_MIROSTAT_TAU = 5.0
        const val DEFAULT_MIROSTAT_ETA = 0.10
        const val DEFAULT_SEED = -1L
        const val MAX_SEED = 4_294_967_295L

        /** v1.3.3-neutral defaults used when the user has not opted into a sampler. */
        val DEFAULT = SamplerConfig()

        /** Decodes the fixed-width sampler ABI; every slot must be present exactly once. */
        fun fromDoubleArray(values: DoubleArray): SamplerConfig {
            require(values.size == SLOT_COUNT) {
                "sampler ABI requires exactly $SLOT_COUNT slots, got ${values.size}"
            }
            return SamplerConfig(
                temperature = values[IDX_TEMPERATURE],
                topK = values[IDX_TOP_K].toIntValue(SamplerConfig.DEFAULT_TOP_K, 0, 1_000_000),
                topP = values[IDX_TOP_P],
                minP = values[IDX_MIN_P],
                repeatPenalty = values[IDX_REPEAT_PENALTY],
                penaltyLastN = values[IDX_PENALTY_LAST_N].toIntValue(SamplerConfig.DEFAULT_PENALTY_LAST_N, 0, 1_000_000),
                frequencyPenalty = values[IDX_FREQUENCY_PENALTY],
                presencePenalty = values[IDX_PRESENCE_PENALTY],
                dryMultiplier = values[IDX_DRY_MULTIPLIER],
                dryBase = values[IDX_DRY_BASE],
                dryAllowedLength = values[IDX_DRY_ALLOWED_LENGTH].toIntValue(SamplerConfig.DEFAULT_DRY_ALLOWED_LENGTH, 0, 128),
                dryPenaltyLastN = values[IDX_DRY_PENALTY_LAST_N].toIntValue(SamplerConfig.DEFAULT_DRY_PENALTY_LAST_N, 0, 1_000_000),
                xtcProbability = values[IDX_XTC_PROBABILITY],
                xtcThreshold = values[IDX_XTC_THRESHOLD],
                topNSigma = values[IDX_TOP_N_SIGMA],
                typicalP = values[IDX_TYPICAL_P],
                dynatempRange = values[IDX_DYNATEMP_RANGE],
                dynatempExponent = values[IDX_DYNATEMP_EXPONENT],
                mirostatEnabled = values[IDX_MIROSTAT_ENABLED].isFinite() && values[IDX_MIROSTAT_ENABLED] != 0.0,
                mirostatTau = values[IDX_MIROSTAT_TAU],
                mirostatEta = values[IDX_MIROSTAT_ETA],
                seed = values[IDX_SEED].toSeed(),
            ).clamped()
        }
    }
}

private fun Double.finiteOr(fallback: Double): Double = if (isFinite()) this else fallback

private fun Double.toIntValue(fallback: Int, minimum: Int, maximum: Int): Int = when {
    !isFinite() -> fallback
    else -> coerceIn(minimum.toDouble(), maximum.toDouble()).toInt()
}

private fun Double.toSeed(): Long = when {
    !isFinite() -> SamplerConfig.DEFAULT_SEED
    this < 0.0 -> SamplerConfig.DEFAULT_SEED
    this >= SamplerConfig.MAX_SEED.toDouble() -> SamplerConfig.MAX_SEED
    else -> toLong()
}

/**
 * Returns the exact native sampler order for [config].
 *
 * Mirostat v2 intentionally bypasses Typical, Top-N-Sigma, top-p, min-p,
 * and XTC. Disabled values are omitted, matching llama.cpp's no-op sampler
 * behavior.
 */
fun describeChain(config: SamplerConfig): List<String> {
    val safe = config.clamped()
    val chain = mutableListOf("penalties")
    if (safe.dryMultiplier > 0.0) chain += "dry"
    if (safe.topK > 0) chain += "top_k"
    if (!safe.mirostatEnabled) {
        if (safe.typicalP < 1.0) chain += "typical"
        if (safe.topNSigma > 0.0) chain += "top_n_sigma"
        if (safe.topP < 1.0) chain += "top_p"
        if (safe.minP > 0.0) chain += "min_p"
        if (safe.xtcProbability > 0.0) chain += "xtc"
    }
    chain += if (safe.dynatempRange > 0.0) "temp_ext" else "temp"
    chain += if (safe.mirostatEnabled) "mirostat_v2" else "dist"
    return chain
}
