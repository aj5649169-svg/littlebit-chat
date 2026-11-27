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
        // 3.9 GB of weights + a 4K F16 KV cache is ~78 % of a 6 GB phone's total RAM.
        val budget = RamBudgetEstimator.estimate(
            modelFileBytes = 3_900_000_000L, nCtx = 4096, useVulkan = false,
            totalRamBytes = gb6, availRamBytes = gb6,
        )
        assertEquals(Risk.DANGER, budget.risk)
    }

    /**
     * The v1.3.1 showstopper: a 12 GB phone with 5.4 GB `availMem` loading a
     * ~5.9 GB estimate (~52 % of total) must stay SAFE. Android's page cache is
     * reclaimable, so `availMem` alone must not block it.
     */
    @Test fun twelveGigabytePhoneLoadsQwen3EightBAtTwoAndFourK() {
        for (ctx in listOf(2048, 4096)) {
            val budget = RamBudgetEstimator.estimate(
                modelFileBytes = 5_000_000_000L, nCtx = ctx, useVulkan = true,
                totalRamBytes = 11_200_000_000L, availRamBytes = 5_400_000_000L,
            )
            assertEquals("ctx=$ctx", Risk.SAFE, budget.risk)
        }
    }

    @Test fun sixGigabytePhoneWarnsForGemma4E2BAtFourK() {
        val budget = RamBudgetEstimator.estimate(
            modelFileBytes = 3_110_000_000L, nCtx = 4096, useVulkan = false,
            totalRamBytes = 6_000_000_000L, availRamBytes = 2_500_000_000L,
        )
        assertTrue(budget.risk == Risk.TIGHT || budget.risk == Risk.DANGER)
    }

    @Test fun replacementModelCreditsTheOutgoingModel() {
        val without = RamBudgetEstimator.estimate(
            modelFileBytes = 5_000_000_000L, nCtx = 2048, useVulkan = false,
            totalRamBytes = 8_000_000_000L, availRamBytes = 2_000_000_000L,
        )
        val with = RamBudgetEstimator.estimate(
            modelFileBytes = 5_000_000_000L, nCtx = 2048, useVulkan = false,
            totalRamBytes = 8_000_000_000L, availRamBytes = 2_000_000_000L,
            currentlyLoadedModelBytes = 4_000_000_000L,
        )
        assertEquals(Risk.DANGER, without.risk)
        assertEquals(4_000_000_000L, with.creditedBytes)
        assertEquals(with.estimateBytes - 4_000_000_000L, with.effectiveBytes())
        assertEquals(Risk.SAFE, with.risk)
    }

    @Test fun smallDeviceLowAvailIsTight() {
        val budget = RamBudgetEstimator.estimate(
            modelFileBytes = 2_500_000_000L, nCtx = 2048, useVulkan = false,
            totalRamBytes = gb6, availRamBytes = gb,
        )
        assertEquals(Risk.TIGHT, budget.risk)
    }

    @Test fun bigPhoneIgnoresReclaimablePageCacheShortfall() {
        // A small model on a 12 GB phone is never blocked just because most of
        // the RAM is currently page cache.
        val budget = RamBudgetEstimator.estimate(
            modelFileBytes = 3L * gb, nCtx = 2048, useVulkan = false,
            totalRamBytes = gb12, availRamBytes = gb / 2,
        )
        assertEquals(Risk.SAFE, budget.risk)
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
