package com.littlebit.chat.data

/**
 * Pure copy/action plan for the pre-load RAM warning.
 *
 * v1.3.1 showed "2K context or CPU avoids most of the peak" with a
 * `Load at 2K Context (Recommended)` button while the requested context was
 * *already* 2048, and offered no Cancel button. The plan below only suggests a
 * context that is actually lower than the requested one and always ends with an
 * explicit Cancel.
 *
 * Pure Kotlin: no Compose or Android imports, fully unit-testable.
 */
object RamDialogPlan {

    const val TWO_K = "Load at 2K Context (Recommended)"
    const val ONE_K = "Load at 1K Context"
    const val CPU = "Load on CPU"
    const val ANYWAY = "Load Anyway"
    const val CANCEL = "Cancel"

    /** The next lower context worth offering, or null when already at 1K. */
    fun lowerContext(requestedCtx: Int): Int? = when {
        requestedCtx > 2048 -> 2048
        requestedCtx > 1024 -> 1024
        else -> null
    }

    fun labelFor(context: Int): String = if (context >= 2048) TWO_K else ONE_K

    data class Plan(
        val title: String,
        val message: String,
        /** The first entry is the primary (confirm) action. */
        val actions: List<String>,
        val lowerContext: Int?,
    )

    fun build(
        risk: RamBudgetEstimator.Risk,
        requestedCtx: Int,
        useVulkan: Boolean,
        modelName: String,
    ): Plan {
        val danger = risk == RamBudgetEstimator.Risk.DANGER
        val lower = lowerContext(requestedCtx)
        val message = when {
            lower == 2048 ->
                "Loading this model at $requestedCtx tokens can freeze the phone. " +
                    "Dropping to 2K context or CPU avoids most of the peak."
            lower == 1024 ->
                "Loading this model at $requestedCtx tokens can freeze the phone. " +
                    "Dropping to 1K context keeps the same model lighter."
            else ->
                "This model is already at the smallest context ($requestedCtx tokens). " +
                    "Close other apps or load on CPU to free headroom."
        }
        val actions = buildList {
            lower?.let { add(labelFor(it)) }
            if (useVulkan) add(CPU)
            add(ANYWAY)
            add(CANCEL)
        }
        return Plan(
            title = if (danger) "Not enough free RAM" else "Tight on RAM",
            message = message,
            actions = actions,
            lowerContext = lower,
        )
    }
}
