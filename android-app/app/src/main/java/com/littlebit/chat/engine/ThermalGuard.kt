package com.littlebit.chat.engine

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Thermal awareness for long local generations. Sustained inference loses
 * 30–40 % of its throughput once the SoC downclocks, so the app warns instead of
 * silently degrading — and lets the user override.
 *
 * [classify] is pure so it can be unit-tested; [read] is the Android probe.
 */
object ThermalGuard {

    /** Headroom at or above this means throttling is imminent (1.0 = no headroom). */
    const val WARM_HEADROOM = 0.95f

    enum class Level { NONE, WARM, SEVERE }

    data class Signal(val thermalStatus: Int, val headroom: Float)

    /**
     * Classifies a reading. `headroom` may be [Float.NaN] (older devices, or
     * `getThermalHeadroom` failing), in which case only the thermal status counts.
     */
    fun classify(thermalStatus: Int, headroom: Float): Level {
        val severe = thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        val nearZero = !headroom.isNaN() && headroom >= WARM_HEADROOM
        val warmStatus = thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        return when {
            severe || nearZero -> Level.SEVERE
            warmStatus -> Level.WARM
            else -> Level.NONE
        }
    }

    fun shouldWarn(thermalStatus: Int, headroom: Float): Boolean =
        classify(thermalStatus, headroom) != Level.NONE

    /** Reads the live thermal state. Never throws; missing APIs read as 0 / NaN. */
    fun read(context: Context): Signal {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return Signal(0, Float.NaN)
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { pm.currentThermalStatus }.getOrDefault(0)
        } else 0
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { pm.getThermalHeadroom(10) }.getOrDefault(Float.NaN)
        } else Float.NaN
        return Signal(status, headroom)
    }

    /** Calm, non-blocking pill text. */
    fun message(level: Level): String = when (level) {
        Level.SEVERE -> "Device hot — throttling now"
        Level.WARM -> "Device warm — throttling likely"
        Level.NONE -> ""
    }
}
