package com.littlebit.chat

import com.littlebit.chat.engine.ThermalGuard
import com.littlebit.chat.engine.ThermalGuard.Level
import org.junit.Assert.*
import org.junit.Test

class ThermalGuardTest {

    // PowerManager thermal status values: 0 none, 1 light, 2 moderate, 3 severe, 4 critical.
    private val none = 0
    private val moderate = 2
    private val severe = 3

    @Test fun coolDeviceIsQuiet() {
        assertEquals(Level.NONE, ThermalGuard.classify(none, 0.4f))
        assertFalse(ThermalGuard.shouldWarn(none, 0.72f))
    }

    @Test fun nearZeroHeadroomWarnsEvenWithoutStatus() {
        assertEquals(Level.SEVERE, ThermalGuard.classify(none, 0.96f))
        assertEquals(Level.SEVERE, ThermalGuard.classify(none, 1f))
        assertTrue(ThermalGuard.shouldWarn(none, 0.95f))
    }

    @Test fun moderateStatusIsWarm() {
        assertEquals(Level.WARM, ThermalGuard.classify(moderate, Float.NaN))
        assertEquals(Level.NONE, ThermalGuard.classify(1, 0.2f))
    }

    @Test fun severeStatusWins() {
        assertEquals(Level.SEVERE, ThermalGuard.classify(severe, 0.1f))
        assertEquals(Level.SEVERE, ThermalGuard.classify(6, Float.NaN))
    }

    @Test fun nanHeadroomNeverThrowsAndIsQuietWhenCool() {
        assertEquals(Level.NONE, ThermalGuard.classify(none, Float.NaN))
        // NaN must not be mistaken for "no headroom left".
        assertNotEquals(Level.SEVERE, ThermalGuard.classify(none, Float.NaN))
    }

    @Test fun messagesAreCalmAndNonBlocking() {
        assertTrue(ThermalGuard.message(Level.WARM).contains("throttling likely"))
        assertTrue(ThermalGuard.message(Level.SEVERE).isNotBlank())
        assertEquals("", ThermalGuard.message(Level.NONE))
    }
}
