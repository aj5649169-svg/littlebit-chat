package com.littlebit.chat

import com.littlebit.chat.studio.ImageProgress
import org.junit.Assert.*
import org.junit.Test

class ImageProgressTest {

    @Test fun loadingPhaseShowsPercent() {
        assertEquals("Loading weights… 50%", ImageProgress.label(ImageProgress.PHASE_LOADING, 5, 10, 0))
    }

    @Test fun loadingPhaseWithoutTotal() {
        assertEquals("Loading weights…", ImageProgress.label(ImageProgress.PHASE_LOADING, 0, 0, 0))
    }

    @Test fun samplingNeverReadsLoaderDenominatorAsSteps() {
        // requested=5 steps, but the loader reported 686 tensors -> must not read as "Step 1 / 686".
        assertEquals("Loading weights…", ImageProgress.label(ImageProgress.PHASE_SAMPLING, 1, 686, 5))
    }

    @Test fun samplingStepLabelUsesRequestedDenominator() {
        assertEquals("Step 1 / 5", ImageProgress.label(ImageProgress.PHASE_SAMPLING, 1, 5, 5))
        assertEquals("Step 3 / 5", ImageProgress.label(ImageProgress.PHASE_SAMPLING, 3, 5, 5))
        assertEquals("Step 5 / 5", ImageProgress.label(ImageProgress.PHASE_SAMPLING, 5, 5, 5))
    }

    @Test fun samplingStepClampedToValidRange() {
        assertEquals("Step 1 / 5", ImageProgress.label(ImageProgress.PHASE_SAMPLING, 0, 5, 5))
        assertEquals("Step 5 / 5", ImageProgress.label(ImageProgress.PHASE_SAMPLING, 9, 5, 5))
    }

    @Test fun samplingWithoutRequestedFallsBackToTotal() {
        assertEquals("Step 1 / 5", ImageProgress.label(ImageProgress.PHASE_SAMPLING, 1, 5, 0))
        assertEquals("Sampling…", ImageProgress.label(ImageProgress.PHASE_SAMPLING, 0, 0, 0))
    }

    @Test fun vaeAndDoneLabels() {
        assertEquals("Decoding VAE…", ImageProgress.label(ImageProgress.PHASE_VAE, 0, 0, 0))
        assertEquals("Done", ImageProgress.label(ImageProgress.PHASE_DONE, 0, 0, 0))
        assertEquals("Ready", ImageProgress.label(ImageProgress.PHASE_IDLE, 0, 0, 0))
    }

    @Test fun percentClampsToZeroAndHundred() {
        assertEquals(0, ImageProgress.percent(0, 0))
        assertEquals(50, ImageProgress.percent(5, 10))
        assertEquals(100, ImageProgress.percent(150, 100))
        assertEquals(0, ImageProgress.percent(5, 0))
    }

    @Test fun ofToleratesNullAndShortArrays() {
        assertEquals(ImageProgress.Snapshot(0, 0, 0, 0), ImageProgress.of(null))
        assertEquals(ImageProgress.Snapshot(0, 0, 0, 0), ImageProgress.of(intArrayOf(1, 2)))
        assertEquals(ImageProgress.Snapshot(2, 3, 5, 5), ImageProgress.of(intArrayOf(2, 3, 5, 5)))
    }
}
