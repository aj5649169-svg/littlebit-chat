package com.littlebit.chat.data

import java.util.Locale

/**
 * Pre-load memory estimate for a local GGUF.
 *
 * A Pixel 6a (6 GB) loading `Gemma 4 E2B Q4` at 4K peaked at 3.61 GB RSS and
 * pushed the kernel into `kswapd0` thrashing (84k major page faults), freezing
 * `system_server`. The weights are mapped and repacked, the KV cache is fully
 * reserved when the context is created, and Vulkan adds driver buffers — so the
 * decision has to be made *before* `llama_model_load_*` runs.
 *
 * Pure Kotlin: no Android imports, fully unit-testable.
 */
object RamBudgetEstimator {

    enum class Risk { SAFE, TIGHT, DANGER }

    /** Weights grow ~15 % once llama.cpp repacks tensors into anonymous memory. */
    const val WEIGHT_OVERHEAD = 1.15

    /** Vulkan driver + command buffers + staging for a fully offloaded model. */
    const val VULKAN_OVERHEAD_BYTES = 320L * 1024 * 1024

    /** F16 KV cache cost per token for a small GQA model (roughly 128 KiB). */
    const val KV_BYTES_PER_TOKEN = 128L * 1024

    /** Q8_0 KV cache is about half of F16 (scales + 8-bit data). */
    const val KV_BYTES_PER_TOKEN_Q8 = 68L * 1024

    const val TIGHT_TOTAL = 0.55
    const val TIGHT_AVAIL = 0.75
    const val DANGER_TOTAL = 0.68
    const val DANGER_AVAIL = 0.90

    /** Phones at or below this much RAM get a 2K default context. */
    const val SMALL_DEVICE_BYTES = (6.5 * 1024 * 1024 * 1024).toLong()

    data class Budget(
        val estimateBytes: Long,
        val totalBytes: Long,
        val availBytes: Long,
        val risk: Risk,
        val kvCacheBytes: Long,
    ) {
        /** "3.6 GB" style text for the dialog. */
        fun estimateText(): String = gb(estimateBytes)
        fun totalText(): String = gb(totalBytes)
        fun availText(): String = gb(availBytes)

        /** `Estimated ~3.6 GB vs 2.1 GB free (5.6 GB total RAM)` */
        fun summary(): String =
            "Estimated ~${estimateText()} vs ${availText()} free (${totalText()} total RAM)"
    }

    fun gb(bytes: Long): String =
        String.format(Locale.ROOT, "%.1f GB", bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))

    fun kvCacheBytes(nCtx: Int, q8Kv: Boolean = false): Long =
        nCtx.coerceAtLeast(0).toLong() * if (q8Kv) KV_BYTES_PER_TOKEN_Q8 else KV_BYTES_PER_TOKEN

    fun estimate(
        modelFileBytes: Long,
        nCtx: Int,
        useVulkan: Boolean,
        totalRamBytes: Long,
        availRamBytes: Long,
        lowMemory: Boolean = false,
        q8Kv: Boolean = false,
    ): Budget {
        val kv = kvCacheBytes(nCtx, q8Kv)
        val weights = (modelFileBytes.coerceAtLeast(0) * WEIGHT_OVERHEAD).toLong()
        val vulkan = if (useVulkan) VULKAN_OVERHEAD_BYTES else 0L
        val estimate = weights + kv + vulkan
        val total = totalRamBytes.coerceAtLeast(1L)
        val avail = availRamBytes.coerceAtLeast(1L)
        val ofTotal = estimate.toDouble() / total
        val ofAvail = estimate.toDouble() / avail
        val risk = when {
            lowMemory -> Risk.DANGER
            ofTotal > DANGER_TOTAL || ofAvail > DANGER_AVAIL -> Risk.DANGER
            ofTotal > TIGHT_TOTAL || ofAvail > TIGHT_AVAIL -> Risk.TIGHT
            else -> Risk.SAFE
        }
        return Budget(estimate, totalRamBytes, availRamBytes, risk, kv)
    }

    /** First launch on a 6 GB-class phone: keep the safe 2K context. */
    fun isSmallDevice(totalRamBytes: Long): Boolean = totalRamBytes in 1..SMALL_DEVICE_BYTES

    /** Context the dialog recommends when the estimate is TIGHT or DANGER. */
    fun safeContext(current: Int): Int = minOf(current, 2048)
}
