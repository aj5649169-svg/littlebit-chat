package com.littlebit.chat

import com.littlebit.chat.ui.advertisedRamGb
import com.littlebit.chat.ui.catalogFit
import com.littlebit.chat.ui.fitsPhone
import com.littlebit.chat.ui.usableRamGb
import com.littlebit.chat.data.GgufHeader
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CatalogTest {
    private val gb = 1_000_000_000L

    @Test fun estimatesFollowTotalDeviceRam() {
        // Classified from total RAM, not the fluctuating availMem value.
        assertEquals("May OOM", catalogFit(2.7, 4 * gb, minimum = 6, recommended = 8))
        assertEquals("Tight — use 2K ctx", catalogFit(3.5, 6 * gb, minimum = 6, recommended = 8))
        assertEquals("Great fit", catalogFit(3.5, 8 * gb, minimum = 6, recommended = 8))
        assertEquals("Great fit", catalogFit(3.5, 12 * gb, minimum = 6, recommended = 8))
        // A "12 GB" phone reports less than 12e9 bytes of totalMem.
        assertEquals("Great fit", catalogFit(3.5, 11_500_000_000L, minimum = 6, recommended = 8))
        assertNotEquals("May OOM", catalogFit(4.69, 11_600_000_000L, minimum = 8, recommended = 12))
    }

    @Test fun twoBitEightBModelStaysVisibleOnTwelveGigabytePhone() {
        val fit = catalogFit(3.5, 11_500_000_000L, minimum = 6, recommended = 8)
        assertEquals("Great fit", fit)
        assertTrue(fitsPhone(fit))
        assertFalse(fitsPhone("May OOM"))
        assertTrue(fitsPhone("Tight — use 2K ctx"))
        assertEquals("Checking memory…", catalogFit(3.5, 0L, minimum = 6, recommended = 8))
    }

    @Test fun advertisedAndUsableRamLeaveOsHeadroom() {
        assertEquals(8, advertisedRamGb(7_400_000_000L))
        assertEquals(12, advertisedRamGb(11_500_000_000L))
        assertEquals(6, advertisedRamGb(5_800_000_000L))
        assertTrue(usableRamGb(12 * gb) >= 9.5)
        assertTrue(usableRamGb(12 * gb) < 12.0)
    }

    @Test fun usableEstimateComesFromMeasuredTotalRamNotTheRoundedTier() {
        // An "8 GB" phone reports ~7.4 GB and a "12 GB" phone ~11.5 GB. Deriving
        // the budget from the rounded tier used to inflate both by 0.5-0.6 GB.
        assertEquals(5.9, usableRamGb(7_400_000_000L), 0.01)
        assertEquals(9.5, usableRamGb(11_500_000_000L), 0.01)
        assertEquals(0.0, usableRamGb(0L), 0.0)
        assertTrue(usableRamGb(7_400_000_000L) < 6.0)
        assertEquals("Great fit", catalogFit(3.5, 7_400_000_000L, minimum = 6, recommended = 8))
        assertEquals("Tight — use 2K ctx", catalogFit(3.5, 5_800_000_000L, minimum = 6, recommended = 8))
    }

    @Test fun headerInspectionDoesNotNeedTensorData() {
        val b = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        fun string(s: String) { val bytes = s.toByteArray(); b.putLong(bytes.size.toLong()); b.put(bytes) }
        b.putInt(0x46554747).putInt(3).putLong(1).putLong(2)
        string("general.architecture"); b.putInt(8); string("qwen3")
        string("qwen3.block_count"); b.putInt(4).putInt(36); b.flip()
        val header = GgufHeader.parse(b, 2700000000L)
        assertEquals("qwen3", header.arch); assertEquals(36, header.layers)
    }
}
