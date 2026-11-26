package com.littlebit.chat

import com.littlebit.chat.ui.catalogFit
import com.littlebit.chat.data.GgufHeader
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CatalogTest {
    @Test fun estimatesUseCurrentlyAvailableMemory() {
        fun fit(total: Int, free: Double) = catalogFit(2.7,total * 1000000000L,(free * 1e9).toLong(),6,8)
        assertEquals("May OOM",fit(4,2.0))
        assertEquals("Tight — use 2K ctx",fit(6,3.8))
        assertEquals("Great fit",fit(8,5.0))
        assertEquals("May OOM",fit(12,1.5))
        assertEquals("Great fit",fit(12,8.0))
    }
    @Test fun headerInspectionDoesNotNeedTensorData() {
        val b = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        fun string(s: String) { val bytes = s.toByteArray(); b.putLong(bytes.size.toLong()); b.put(bytes) }
        b.putInt(0x46554747).putInt(3).putLong(1).putLong(2)
        string("general.architecture"); b.putInt(8); string("qwen3")
        string("qwen3.block_count"); b.putInt(4).putInt(36); b.flip()
        val header = GgufHeader.parse(b,2700000000L)
        assertEquals("qwen3",header.arch); assertEquals(36,header.layers)
    }
}
