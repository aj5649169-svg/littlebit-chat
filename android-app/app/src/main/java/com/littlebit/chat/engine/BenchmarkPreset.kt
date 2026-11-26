package com.littlebit.chat.engine

/** Benchmark sizes offered by the pre-run setup dialog. */
enum class BenchmarkPreset(val label: String, val promptTokens: Int, val genTokens: Int) {
    QUICK("Quick · 64 / 64", 64, 64),
    STANDARD("Standard · 128 / 128", 128, 128),
    STRESS("Stress · 256 / 256", 256, 256);

    /** The fixed sentence is ~14 tokens; repeat it to reach the preset size. */
    fun promptText(base: String = "Explain how rain forms, step by step."): String {
        val repeats = (promptTokens / 14).coerceAtLeast(1)
        return (1..repeats).joinToString(" ") { base }
    }

    companion object {
        fun byLabel(label: String): BenchmarkPreset =
            entries.firstOrNull { it.label == label } ?: QUICK
    }
}
