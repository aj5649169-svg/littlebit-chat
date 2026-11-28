package com.littlebit.chat

import com.littlebit.chat.engine.SamplerConfig
import com.littlebit.chat.engine.describeChain
import org.junit.Assert.assertEquals
import org.junit.Test

class SamplerOrderTest {

    @Test fun defaultChainKeepsV133Order() {
        assertEquals(
            listOf("penalties", "dry", "top_k", "top_p", "min_p", "temp", "dist"),
            describeChain(SamplerConfig.DEFAULT),
        )
    }

    @Test fun fullCustomChainUsesCanonicalOrder() {
        val config = SamplerConfig.DEFAULT.copy(
            dryMultiplier = 1.0,
            topK = 80,
            typicalP = 0.80,
            topNSigma = 2.0,
            topP = 0.85,
            minP = 0.10,
            xtcProbability = 0.20,
            dynatempRange = 0.50,
        )

        assertEquals(
            listOf(
                "penalties", "dry", "top_k", "typical", "top_n_sigma",
                "top_p", "min_p", "xtc", "temp_ext", "dist",
            ),
            describeChain(config),
        )
    }

    @Test fun dynatempReplacesStandardTemperature() {
        assertEquals("temp", describeChain(SamplerConfig.DEFAULT).last { it == "temp" || it == "temp_ext" })
        assertEquals(
            "temp_ext",
            describeChain(SamplerConfig.DEFAULT.copy(dynatempRange = 0.50))
                .last { it == "temp" || it == "temp_ext" },
        )
    }

    @Test fun mirostatModeUsesMirostatInsteadOfDist() {
        val config = SamplerConfig.DEFAULT.copy(
            topK = 0,
            typicalP = 0.80,
            topNSigma = 2.0,
            topP = 0.80,
            minP = 0.10,
            xtcProbability = 0.20,
            mirostatEnabled = true,
        )

        assertEquals(
            listOf("penalties", "dry", "temp", "mirostat_v2"),
            describeChain(config),
        )
    }
}
