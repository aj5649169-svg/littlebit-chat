package com.littlebit.chat.data

/** Lossless SharedPreferences encoding for sampler doubles, with legacy migrations. */
internal object SamplerPreferenceCodec {

    fun encode(value: Double): Long = value.toRawBits()

    fun decode(stored: Any?): Double? = when (stored) {
        is Long -> Double.fromBits(stored)
        is Float -> stored.toDouble()
        is Int -> stored.toDouble()
        else -> null
    }

    /** The original top-p/min-p keys stored integer percentages. */
    fun decodePercent(stored: Any?): Double? = when (stored) {
        is Long -> Double.fromBits(stored)
        is Int -> stored / 100.0
        is Float -> stored.toDouble()
        else -> null
    }
}
