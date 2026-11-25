package com.littlebit.chat.engine

import java.util.Locale

val outputLimits = listOf(256, 512, 1024, 2048, 4096, 8192)

fun outputBudget(limit: Int, capacity: Int, used: Int) =
    minOf(limit, (capacity - used - 1).coerceAtLeast(0))

fun duration(ms: Double): String {
    val seconds = ms.toLong() / 1000
    return if (ms < 60_000) String.format(Locale.ROOT, "%.1fs", ms / 1000)
    else "${seconds / 60}m ${seconds % 60}s"
}

data class ReplyTiming(val promptTokens: Int, val promptMs: Double, val tokens: Int, val genMs: Double,
                       val firstMs: Double, val used: Int, val capacity: Int) {
    val promptRate get() = if (promptMs > 0) promptTokens * 1000 / promptMs else 0.0
    val genRate get() = if (genMs > 0) tokens * 1000 / genMs else 0.0
    fun caption(mode: String): String {
        val gen = String.format(Locale.ROOT, "%.1f t/s (%s)", genRate, duration(genMs))
        return when (mode) {
            "Detailed" -> String.format(Locale.ROOT, "Prompt: %.1f t/s (%s) · Gen: %s", promptRate, duration(promptMs), gen)
            "Off" -> ""
            else -> gen
        }
    }
    fun followedBy(next: ReplyTiming?) = next?.copy(promptTokens = promptTokens + next.promptTokens,
        promptMs = promptMs + next.promptMs, tokens = tokens + next.tokens, genMs = genMs + next.genMs,
        firstMs = firstMs) ?: this
}
