package com.littlebit.chat

import com.littlebit.chat.engine.ReplyTiming
import com.littlebit.chat.engine.outputBudget
import org.junit.Assert.*
import org.junit.Test

class ContextBudgetTest {

    @Test fun generationBudgetNeverOverrunsTheContext() {
        assertEquals(4096, outputBudget(4096, 8192, 0))
        assertEquals(4096, outputBudget(4096, 8192, 2048))
        assertEquals(47, outputBudget(4096, 2048, 2000))
        assertEquals(0, outputBudget(4096, 2048, 2048))
        assertEquals(0, outputBudget(4096, 2048, 5000))
        assertEquals(0, outputBudget(4096, 0, 0))
        assertEquals(1, outputBudget(4096, 2048, 2046))
    }

    @Test fun contextUsageReportingUsesTheLiveCounters() {
        val timing = ReplyTiming(152, 1400.0, 1168, 84000.0, 1420.0, 1320, 2048)
        assertEquals(1320, timing.used)
        assertEquals(2048, timing.capacity)
        assertTrue(timing.used < timing.capacity)
    }

    @Test fun continuationMergesPromptAndGenerationStats() {
        val first = ReplyTiming(100, 1000.0, 50, 5000.0, 900.0, 150, 2048)
        val next = ReplyTiming(10, 100.0, 25, 2000.0, 300.0, 185, 2048)
        val merged = first.followedBy(next)
        assertEquals(110, merged.promptTokens)
        assertEquals(75, merged.tokens)
        assertEquals(1100.0, merged.promptMs, 0.0)
        assertEquals(7000.0, merged.genMs, 0.0)
        assertEquals(first.firstMs, merged.firstMs, 0.0)
        assertSame(first, first.followedBy(null))
    }
}
