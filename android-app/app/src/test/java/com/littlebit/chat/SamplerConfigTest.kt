package com.littlebit.chat

import com.littlebit.chat.engine.SamplerConfig
import com.littlebit.chat.engine.describeChain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplerConfigTest {

    @Test fun roundTripsEverySlot() {
        val config = SamplerConfig(
            temperature = 1.25,
            topK = 80,
            topP = 0.85,
            minP = 0.08,
            repeatPenalty = 1.22,
            penaltyLastN = 128,
            frequencyPenalty = 0.15,
            presencePenalty = -0.10,
            dryMultiplier = 1.10,
            dryBase = 1.80,
            dryAllowedLength = 3,
            dryPenaltyLastN = 96,
            xtcProbability = 0.25,
            xtcThreshold = 0.20,
            topNSigma = 2.5,
            typicalP = 0.90,
            dynatempRange = 0.40,
            dynatempExponent = 1.20,
            mirostatEnabled = true,
            mirostatTau = 4.5,
            mirostatEta = 0.08,
            seed = 42,
        )

        val encoded = config.toDoubleArray()

        assertEquals(22, encoded.size)
        assertArrayEquals(
            doubleArrayOf(
                1.25, 80.0, 0.85, 0.08, 1.22, 128.0, 0.15, -0.10,
                1.10, 1.80, 3.0, 96.0, 0.25, 0.20, 2.5, 0.90,
                0.40, 1.20, 1.0, 4.5, 0.08, 42.0,
            ),
            encoded,
            0.0,
        )
        assertEquals(config, SamplerConfig.fromDoubleArray(encoded))
    }

    @Test fun rejectsUndersizedAbiArray() {
        try {
            SamplerConfig.fromDoubleArray(DoubleArray(SamplerConfig.SLOT_COUNT - 1))
            throw AssertionError("Expected an undersized sampler ABI to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun rejectsOversizedAbiArray() {
        try {
            SamplerConfig.fromDoubleArray(DoubleArray(SamplerConfig.SLOT_COUNT + 1))
            throw AssertionError("Expected an oversized sampler ABI to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun clampsEveryOutOfRangeParameter() {
        val clamped = SamplerConfig(
            temperature = 9.0,
            topK = -4,
            topP = 4.0,
            minP = -0.2,
            repeatPenalty = 0.25,
            penaltyLastN = -8,
            frequencyPenalty = 8.0,
            presencePenalty = -8.0,
            dryMultiplier = 7.0,
            dryBase = 0.25,
            dryAllowedLength = -2,
            dryPenaltyLastN = -9,
            xtcProbability = 3.0,
            xtcThreshold = 0.9,
            topNSigma = 500.0,
            typicalP = 2.0,
            dynatempRange = 12.0,
            dynatempExponent = 0.0,
            mirostatEnabled = true,
            mirostatTau = 0.0,
            mirostatEta = 4.0,
            seed = 4_294_967_296L,
        ).clamped()

        assertEquals(2.0, clamped.temperature, 0.0)
        assertEquals(0, clamped.topK)
        assertEquals(1.0, clamped.topP, 0.0)
        assertEquals(0.0, clamped.minP, 0.0)
        assertEquals(1.0, clamped.repeatPenalty, 0.0)
        assertEquals(0, clamped.penaltyLastN)
        assertEquals(2.0, clamped.frequencyPenalty, 0.0)
        assertEquals(-2.0, clamped.presencePenalty, 0.0)
        assertEquals(2.0, clamped.dryMultiplier, 0.0)
        assertEquals(1.0, clamped.dryBase, 0.0)
        assertEquals(0, clamped.dryAllowedLength)
        assertEquals(0, clamped.dryPenaltyLastN)
        assertEquals(1.0, clamped.xtcProbability, 0.0)
        assertEquals(0.5, clamped.xtcThreshold, 0.0)
        assertEquals(100.0, clamped.topNSigma, 0.0)
        assertEquals(1.0, clamped.typicalP, 0.0)
        assertEquals(4.0, clamped.dynatempRange, 0.0)
        assertEquals(0.01, clamped.dynatempExponent, 0.0)
        assertEquals(0.01, clamped.mirostatTau, 0.0)
        assertEquals(1.0, clamped.mirostatEta, 0.0)
        assertEquals(4_294_967_295L, clamped.seed)
    }

    @Test fun clampsNonFiniteValuesToSafeDefaults() {
        val clamped = SamplerConfig(
            temperature = Double.NaN,
            topP = Double.POSITIVE_INFINITY,
            minP = Double.NEGATIVE_INFINITY,
            repeatPenalty = Double.NaN,
            frequencyPenalty = Double.POSITIVE_INFINITY,
            presencePenalty = Double.NEGATIVE_INFINITY,
            dryMultiplier = Double.NaN,
            dryBase = Double.POSITIVE_INFINITY,
            xtcProbability = Double.NaN,
            xtcThreshold = Double.NEGATIVE_INFINITY,
            topNSigma = Double.NaN,
            typicalP = Double.POSITIVE_INFINITY,
            dynatempRange = Double.NaN,
            dynatempExponent = Double.NEGATIVE_INFINITY,
            mirostatTau = Double.NaN,
            mirostatEta = Double.POSITIVE_INFINITY,
        ).clamped()

        assertEquals(SamplerConfig.DEFAULT.temperature, clamped.temperature, 0.0)
        assertEquals(SamplerConfig.DEFAULT.topP, clamped.topP, 0.0)
        assertEquals(SamplerConfig.DEFAULT.minP, clamped.minP, 0.0)
        assertEquals(SamplerConfig.DEFAULT.repeatPenalty, clamped.repeatPenalty, 0.0)
        assertEquals(SamplerConfig.DEFAULT.frequencyPenalty, clamped.frequencyPenalty, 0.0)
        assertEquals(SamplerConfig.DEFAULT.presencePenalty, clamped.presencePenalty, 0.0)
        assertEquals(SamplerConfig.DEFAULT.dryMultiplier, clamped.dryMultiplier, 0.0)
        assertEquals(SamplerConfig.DEFAULT.dryBase, clamped.dryBase, 0.0)
        assertEquals(SamplerConfig.DEFAULT.xtcProbability, clamped.xtcProbability, 0.0)
        assertEquals(SamplerConfig.DEFAULT.xtcThreshold, clamped.xtcThreshold, 0.0)
        assertEquals(SamplerConfig.DEFAULT.topNSigma, clamped.topNSigma, 0.0)
        assertEquals(SamplerConfig.DEFAULT.typicalP, clamped.typicalP, 0.0)
        assertEquals(SamplerConfig.DEFAULT.dynatempRange, clamped.dynatempRange, 0.0)
        assertEquals(SamplerConfig.DEFAULT.dynatempExponent, clamped.dynatempExponent, 0.0)
        assertEquals(SamplerConfig.DEFAULT.mirostatTau, clamped.mirostatTau, 0.0)
        assertEquals(SamplerConfig.DEFAULT.mirostatEta, clamped.mirostatEta, 0.0)
    }

    @Test fun mirostatExcludesConflictingSamplers() {
        val chain = describeChain(
            SamplerConfig.DEFAULT.copy(
                typicalP = 0.80,
                topNSigma = 2.0,
                topP = 0.80,
                minP = 0.10,
                xtcProbability = 0.20,
                mirostatEnabled = true,
            ),
        )

        assertFalse(chain.contains("typical"))
        assertFalse(chain.contains("top_n_sigma"))
        assertFalse(chain.contains("top_p"))
        assertFalse(chain.contains("min_p"))
        assertFalse(chain.contains("xtc"))
        assertTrue(chain.contains("mirostat_v2"))
        assertFalse(chain.contains("dist"))
    }

    @Test fun seedLockAndRandomSeedUseDistinctAbiValues() {
        assertEquals(-1L, SamplerConfig.DEFAULT.seed)
        assertEquals(-1.0, SamplerConfig.DEFAULT.toDoubleArray()[SamplerConfig.IDX_SEED], 0.0)

        val locked = SamplerConfig.DEFAULT.copy(seed = 42)
        assertEquals(42L, locked.seed)
        assertEquals(42.0, locked.toDoubleArray()[SamplerConfig.IDX_SEED], 0.0)

        val maximumSeed = SamplerConfig.DEFAULT.copy(seed = SamplerConfig.MAX_SEED)
        assertEquals(SamplerConfig.MAX_SEED, maximumSeed.toDoubleArray()[SamplerConfig.IDX_SEED].toLong())
        assertEquals(
            SamplerConfig.MAX_SEED,
            SamplerConfig.fromDoubleArray(maximumSeed.toDoubleArray()).seed,
        )
    }

    @Test fun defaultsReproduceV133NeutralChain() {
        val defaults = SamplerConfig.DEFAULT

        assertEquals(0.7, defaults.temperature, 0.0)
        assertEquals(40, defaults.topK)
        assertEquals(0.90, defaults.topP, 0.0)
        assertEquals(0.05, defaults.minP, 0.0)
        assertEquals(1.18, defaults.repeatPenalty, 0.0)
        assertEquals(64, defaults.penaltyLastN)
        assertEquals(0.0, defaults.frequencyPenalty, 0.0)
        assertEquals(0.0, defaults.presencePenalty, 0.0)
        assertEquals(0.8, defaults.dryMultiplier, 0.0)
        assertEquals(1.75, defaults.dryBase, 0.0)
        assertEquals(2, defaults.dryAllowedLength)
        assertEquals(64, defaults.dryPenaltyLastN)
        assertEquals(0.0, defaults.xtcProbability, 0.0)
        assertEquals(0.10, defaults.xtcThreshold, 0.0)
        assertEquals(0.0, defaults.topNSigma, 0.0)
        assertEquals(1.0, defaults.typicalP, 0.0)
        assertEquals(0.0, defaults.dynatempRange, 0.0)
        assertEquals(1.0, defaults.dynatempExponent, 0.0)
        assertFalse(defaults.mirostatEnabled)
        assertEquals(5.0, defaults.mirostatTau, 0.0)
        assertEquals(0.10, defaults.mirostatEta, 0.0)
        assertEquals(
            listOf("penalties", "dry", "top_k", "top_p", "min_p", "temp", "dist"),
            describeChain(defaults),
        )
    }
}
