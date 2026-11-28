package com.littlebit.chat.studio

/**
 * Turns the native progress snapshot into the one string the Studio shows.
 *
 * The native side reports four numbers because stable-diffusion.cpp drives a
 * single callback from two unrelated producers: the tensor loader (index over
 * the checkpoint's tensors — about 686 for an SD 1.5 file) and the sampler
 * (step over the requested steps). Treating the loader's denominator as a step
 * count is what surfaced as "Step 1 / 686" with Steps = 1.
 *
 * Rule: a sampling label may only ever use the *requested* step count as its
 * denominator, and a loader callback is never allowed to look like a step.
 *
 * Pure Kotlin so it is unit-testable on the JVM without Android.
 */
object ImageProgress {

    const val PHASE_IDLE = 0
    const val PHASE_LOADING = 1
    const val PHASE_SAMPLING = 2
    const val PHASE_VAE = 3
    const val PHASE_DONE = 4

    /** Safe accessor for `SdNative.progress()`; tolerates short/absent arrays. */
    fun of(snapshot: IntArray?): Snapshot {
        if (snapshot == null || snapshot.size < 4) return Snapshot(PHASE_IDLE, 0, 0, 0)
        return Snapshot(snapshot[0], snapshot[1], snapshot[2], snapshot[3])
    }

    data class Snapshot(val phase: Int, val step: Int, val total: Int, val requested: Int) {
        fun label(): String = ImageProgress.label(phase, step, total, requested)
    }

    fun percent(done: Int, all: Int): Int {
        if (all <= 0) return 0
        val value = done.toLong() * 100L / all.toLong()
        return value.coerceIn(0L, 100L).toInt()
    }

    fun label(phase: Int, step: Int, total: Int, requested: Int): String = when (phase) {
        PHASE_LOADING ->
            if (total > 0) "Loading weights… ${percent(step, total)}%" else "Loading weights…"

        PHASE_SAMPLING -> {
            // Defensive: if the native denominator does not match what we asked
            // for, this is the tensor loader and must not read as a step.
            if (requested > 0 && total != requested) {
                "Loading weights…"
            } else {
                val denom = if (requested > 0) requested else total
                if (denom <= 0) "Sampling…" else "Step ${step.coerceIn(1, denom)} / $denom"
            }
        }

        PHASE_VAE -> "Decoding VAE…"
        PHASE_DONE -> "Done"
        else -> "Ready"
    }
}
