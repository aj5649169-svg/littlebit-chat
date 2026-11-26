package com.littlebit.chat

import org.junit.Assert.*
import org.junit.Test

class ForegroundServiceTest {
    @Test fun notificationsAreOptionalOnAndroid13AndNewer() {
        assertTrue(ForegroundServicePolicy.shouldStartForegroundService(30, notificationsGranted = false))
        assertTrue(ForegroundServicePolicy.shouldStartForegroundService(33, notificationsGranted = true))
        assertFalse(ForegroundServicePolicy.shouldStartForegroundService(33, notificationsGranted = false))
        assertFalse(ForegroundServicePolicy.shouldStartForegroundService(36, notificationsGranted = false))
    }

    @Test fun stopActionOnlyCancelsTheMatchingGeneration() {
        assertTrue(ForegroundServicePolicy.shouldCancelOperation(ForegroundServicePolicy.ACTION_STOP, versionMatches = true))
        assertFalse(ForegroundServicePolicy.shouldCancelOperation(ForegroundServicePolicy.ACTION_STOP, versionMatches = false))
        assertFalse(ForegroundServicePolicy.shouldCancelOperation(null, versionMatches = true))
        assertFalse(ForegroundServicePolicy.shouldCancelOperation("android.intent.action.MAIN", versionMatches = true))
    }

    @Test fun serviceOnlyStopsItselfAfterTheForegroundPromotion() {
        assertTrue(
            ForegroundServicePolicy.shouldStopSelf(
                ForegroundServicePolicy.ACTION_STOP, operationPending = false, versionMatches = true,
            ),
        )
        assertFalse(
            ForegroundServicePolicy.shouldStopSelf(
                ForegroundServicePolicy.ACTION_STOP, operationPending = true, versionMatches = true,
            ),
        )
        assertFalse(
            ForegroundServicePolicy.shouldStopSelf(
                null, operationPending = false, versionMatches = true,
            ),
        )
        assertTrue(ForegroundServicePolicy.isStopAction(ForegroundServicePolicy.ACTION_STOP))
        assertFalse(ForegroundServicePolicy.isStopAction(null))
    }

    @Test fun staleStopCannotEndANewerGeneration() {
        // start -> stop -> start: the second start bumped revision, so the stop
        // intent from the first generation must be ignored.
        assertFalse(
            ForegroundServicePolicy.shouldStopSelf(
                ForegroundServicePolicy.ACTION_STOP, operationPending = false, versionMatches = false,
            ),
        )
        assertTrue(
            ForegroundServicePolicy.shouldStopSelf(
                ForegroundServicePolicy.ACTION_STOP, operationPending = false, versionMatches = true,
            ),
        )
    }

    @Test fun foregroundTypeIsOnlySetWhereTheApiSupportsIt() {
        assertEquals(0, ForegroundServicePolicy.foregroundServiceType(33))
        assertTrue(ForegroundServicePolicy.foregroundServiceType(34) > 0)
        assertEquals(
            ForegroundServicePolicy.foregroundServiceType(34),
            ForegroundServicePolicy.foregroundServiceType(36),
        )
    }
}
