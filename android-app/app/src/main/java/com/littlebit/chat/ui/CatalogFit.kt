package com.littlebit.chat.ui

/**
 * Android keeps roughly 1.5-3 GB of RAM for the kernel, system UI and
 * background services, and advertised capacity is rounded. Plan against total
 * RAM instead of the instantaneous availMem value, which fluctuates with
 * unrelated apps and used to hide 2-bit 8B models on 12 GB phones.
 */

/** Marketed RAM in GB: a "12 GB" phone reports about 11.4-11.8 GB totalMem. */
fun advertisedRamGb(totalBytes: Long): Int {
    if (totalBytes <= 0L) return 0
    val gb = totalBytes / 1e9
    val tiers = listOf(2, 3, 4, 6, 8, 12, 16, 24, 32)
    return tiers.firstOrNull { gb <= it + 0.6 } ?: (gb + 0.5).toInt()
}

/**
 * RAM an app can realistically allocate after the OS and its services.
 *
 * The reserve is subtracted from the *measured* `totalMem`, not from the
 * rounded advertised tier: an "8 GB" phone reports ~7.4 GB, so going through
 * the tier would overstate the budget by ~0.6 GB and hide that a 3.5 GB 2-bit
 * 8B model is tight on 8 GB devices.
 */
fun usableRamGb(totalBytes: Long): Double {
    if (totalBytes <= 0L) return 0.0
    val advertised = advertisedRamGb(totalBytes)
    val reserve = when {
        advertised >= 16 -> 3.0
        advertised >= 12 -> 2.0
        advertised >= 8 -> 1.5
        advertised >= 6 -> 1.25
        else -> 1.0
    }
    return ((totalBytes / 1e9) - reserve).coerceAtLeast(0.5)
}

/**
 * `Great fit` / `Tight — use 2K ctx` / `May OOM` for a catalog entry, based on
 * the phone's total RAM. [sizeGb] is the weight file; kv-cache and backend
 * overhead are approximated with a fixed 0.4 GB working-set allowance.
 */
fun catalogFit(sizeGb: Double, totalBytes: Long, minimum: Int, recommended: Int): String {
    if (totalBytes <= 0L) return "Checking memory…"
    val advertised = advertisedRamGb(totalBytes)
    val usable = usableRamGb(totalBytes)
    val workingSet = sizeGb.coerceAtLeast(0.0) + 0.4 // mmap + kv-cache + backend overhead
    return when {
        advertised < minimum || workingSet > usable -> "May OOM"
        advertised < recommended || workingSet > usable * 0.85 -> "Tight — use 2K ctx"
        else -> "Great fit"
    }
}

/** `Fits my phone` keeps everything that the device can plausibly run. */
fun fitsPhone(fit: String): Boolean = fit == "Great fit" || fit == "Tight — use 2K ctx"
