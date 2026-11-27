package com.littlebit.chat.studio

/**
 * Compact hint under the Steps slider.
 *
 * A standard (non-distilled) SD 1.5 checkpoint at 1-2 steps produces noise.
 * SD-Turbo / LCM / Lightning checkpoints are the ones designed for 1-4 steps,
 * so they never get the hint.
 *
 * Pure Kotlin: unit-testable without Android.
 */
object ImageStepHint {

    const val HINT =
        "Standard SD 1.5 usually needs 12–20 steps (use SD-Turbo or LCM for 1–4 steps)."

    private val distilled = listOf("turbo", "lcm", "lightning", "hyper", "dmd", "lcm-lora")

    private val standard = listOf(
        "stable-diffusion-v1-5",
        "stable_diffusion_v1-5",
        "sd-v1-5",
        "sd_v1-5",
        "inpainting",
        "emaonly",
    )

    /** Null when no hint applies. */
    fun forModel(fileName: String, steps: Int): String? {
        if (steps > 2 || fileName.isBlank()) return null
        val name = fileName.lowercase()
        if (distilled.any { name.contains(it) }) return null
        return if (standard.any { name.contains(it) }) HINT else null
    }
}
