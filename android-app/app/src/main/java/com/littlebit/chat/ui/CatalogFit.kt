package com.littlebit.chat.ui

/** Approximate advertised RAM tiers tolerate memory reserved by the kernel. */
fun catalogFit(sizeGb: Double, totalBytes: Long, freeBytes: Long, minimum: Int, recommended: Int): String {
    val tier = totalBytes / 1e9 + 0.6
    val workingSet = sizeGb * 1e9 + 384L * 1024 * 1024
    return when {
        freeBytes <= 0 -> "Checking memory…"
        tier < minimum || workingSet > freeBytes -> "May OOM"
        tier < recommended || workingSet > freeBytes * .75 -> "Tight — use 2K ctx"
        else -> "Great fit"
    }
}
