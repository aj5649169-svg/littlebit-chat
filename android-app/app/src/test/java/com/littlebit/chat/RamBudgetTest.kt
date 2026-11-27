package com.littlebit.chat

import com.littlebit.chat.data.RamBudgetEstimator
import com.littlebit.chat.data.RamBudgetEstimator.Risk
import org.junit.Assert.*
import org.junit.Test

class RamBudgetTest {

    private val gb = 1024L * 1024 * 1024
    private val gb6 = 6L * gb          // Pixel 6a
    private val gb12 = 12L * gb        // modern flagship

    @Test fun twelveGigabytePhoneIsSafe() {
        val budget = RamBudgetEstimator.estimate(
            modelFileBytes = 3L * gb, nCtx = 2048, useVulkan = false,
            totalRamBytes = gb12, availRamBytes = 6L * gb,
        )
        assertEquals(Risk.SAFE, budget.risk)
        assertTrue(budget.estimateBytes > 3L * gb)
        assertTrue(budget.estimateBytes < 4L * gb)
    }

    @Test fun sixGigabytePhoneIsTightAtTwoK() {
        val budget = RamBudgetEstimator.estimate(
            modelFileBytes = 3_200_000_000L, nCtx = 2048, useVulkan = false,
            totalRamBytes = gb6, availRamBytes = gb6,
        )
        assertEquals(Risk.TIGHT, budget.risk)
    }

    @Test fun sixGigabytePhoneIsDangerAtFourK() {
        val budget = RamBudgetEstimator.estimate(
            modelFileBytes = 3_600_000_000L, nCtx = 4096, useVulkan = false,
            totalRamBytes = gb6, availRamBytes = gb6,
        )
        assertEquals(Risk.DANGER, budget.risk)
    }

    @Test fun lowFreeMemoryIsDangerEvenOnBigPhone() {
        val budget = RamBudgetEstimator.estimate(
            modelFileBytes = 3L * gb, nCtx = 2048, useVulkan = false,
            totalRamBytes = gb12, availRamBytes = 2L * gb,
        )
        assertEquals(Risk.DANGER, budget.risk)
    }

    @Test fun lowMemoryFlagForcesDanger() {
        val budget = RamBudgetEstimator.estimate(
            modelFileBytes = 1L * gb, nCtx = 1024, useVulkan = false,
            totalRamBytes = gb12, availRamBytes = 8L * gb, lowMemory = true,
        )
        assertEquals(Risk.DANGER, budget.risk)
    }

    @Test fun vulkanAddsOverhead() {
        val cpu = RamBudgetEstimator.estimate(3L * gb, 2048, useVulkan = false, gb6, gb6)
        val vulkan = RamBudgetEstimator.estimate(3L * gb, 2048, useVulkan = true, gb6, gb6)
        assertEquals(RamBudgetEstimator.VULKAN_OVERHEAD_BYTES, vulkan.estimateBytes - cpu.estimateBytes)
    }

    @Test fun contextSizeScalesTheCache() {
        assertEquals(256L * RamBudgetEstimator.KV_BYTES_PER_TOKEN, RamBudgetEstimator.kvCacheBytes(256))
        assertEquals(
            RamBudgetEstimator.kvCacheBytes(4096) * 2,
            RamBudgetEstimator.kvCacheBytes(8192),
        )
        assertTrue(RamBudgetEstimator.kvCacheBytes(4096, q8Kv = true) < RamBudgetEstimator.kvCacheBytes(4096))
    }

    @Test fun summaryAndFormattingAreReadable() {
        val budget = RamBudgetEstimator.estimate(3L * gb, 2048, false, gb6, 3L * gb)
        val text = budget.summary()
        assertTrue(text.startsWith("Estimated ~"))
        assertTrue(text.contains("free (6.0 GB total RAM)"))
        assertEquals("1.5 GB", RamBudgetEstimator.gb((1.5 * gb).toLong()))
    }

    @Test fun smallDeviceAndSafeContext() {
        assertTrue(RamBudgetEstimator.isSmallDevice(gb6))
        assertTrue(RamBudgetEstimator.isSmallDevice((6.5 * gb).toLong()))
        assertFalse(RamBudgetEstimator.isSmallDevice(gb12))
        assertFalse(RamBudgetEstimator.isSmallDevice(0L))
        assertEquals(2048, RamBudgetEstimator.safeContext(4096))
        assertEquals(1024, RamBudgetEstimator.safeContext(1024))
    }
}
