package com.littlebit.chat

import com.littlebit.chat.data.SamplerPreferenceCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class SamplerPreferenceCodecTest {

    @Test fun rawBitsRoundTripWithoutRounding() {
        val value = 0.855

        val encoded = SamplerPreferenceCodec.encode(value)

        assertEquals(value, SamplerPreferenceCodec.decode(encoded)!!, 0.0)
        assertEquals(value, SamplerPreferenceCodec.decodePercent(encoded)!!, 0.0)
    }

    @Test fun legacyIntegerPercentMigratesToProbability() {
        assertEquals(0.86, SamplerPreferenceCodec.decodePercent(86)!!, 0.0)
        assertEquals(0.05, SamplerPreferenceCodec.decodePercent(5)!!, 0.0)
    }

    @Test fun legacyFloatMigratesWithoutChangingStoredValue() {
        assertEquals(0.9f.toDouble(), SamplerPreferenceCodec.decode(0.9f)!!, 0.0)
        assertEquals(1.18f.toDouble(), SamplerPreferenceCodec.decode(1.18f)!!, 0.0)
    }
}
