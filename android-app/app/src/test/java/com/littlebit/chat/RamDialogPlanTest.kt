package com.littlebit.chat

import com.littlebit.chat.data.RamBudgetEstimator.Risk
import com.littlebit.chat.data.RamDialogPlan
import org.junit.Assert.*
import org.junit.Test

class RamDialogPlanTest {

    @Test fun atTwoKNeverOffersTwoKAgainButOffersOneK() {
        val plan = RamDialogPlan.build(Risk.DANGER, requestedCtx = 2048, useVulkan = true, modelName = "qwen.gguf")
        assertFalse(plan.actions.contains(RamDialogPlan.TWO_K))
        assertFalse(plan.message.contains("2K"))
        assertTrue(plan.actions.contains(RamDialogPlan.ONE_K))
        assertTrue(plan.actions.contains(RamDialogPlan.CPU))
        assertTrue(plan.actions.contains(RamDialogPlan.ANYWAY))
        assertTrue(plan.actions.contains(RamDialogPlan.CANCEL))
        assertEquals(1024, plan.lowerContext)
        assertEquals("Not enough free RAM", plan.title)
    }

    @Test fun fourKWithoutVulkanOffersTwoKAndNoCpu() {
        val plan = RamDialogPlan.build(Risk.TIGHT, requestedCtx = 4096, useVulkan = false, modelName = "gemma.gguf")
        assertTrue(plan.actions.contains(RamDialogPlan.TWO_K))
        assertFalse(plan.actions.contains(RamDialogPlan.CPU))
        assertTrue(plan.actions.contains(RamDialogPlan.CANCEL))
        assertTrue(plan.actions.contains(RamDialogPlan.ANYWAY))
        assertEquals(2048, plan.lowerContext)
        assertEquals("Tight on RAM", plan.title)
    }

    @Test fun atOrBelowOneKOmitsTheLowerContextButton() {
        val plan = RamDialogPlan.build(Risk.DANGER, requestedCtx = 1024, useVulkan = false, modelName = "small.gguf")
        assertNull(plan.lowerContext)
        assertFalse(plan.actions.any { it == RamDialogPlan.TWO_K || it == RamDialogPlan.ONE_K })
        assertEquals(listOf(RamDialogPlan.ANYWAY, RamDialogPlan.CANCEL), plan.actions)
    }

    @Test fun lowerContextNeverRaisesTheWindow() {
        assertNull(RamDialogPlan.lowerContext(1024))
        assertEquals(1024, RamDialogPlan.lowerContext(2048))
        assertEquals(2048, RamDialogPlan.lowerContext(4096))
    }
}
