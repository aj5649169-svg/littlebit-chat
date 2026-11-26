package com.littlebit.chat.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

data class GgufHeader(val arch: String, val quant: String, val layers: Int, val context: Int,
                      val bytes: Long, val kvWidth: Int) {
    fun fit(ctx: Int, available: Long): String {
        val estimate = bytes.toDouble() + 4.0 * layers * kvWidth * ctx + 384 * 1048576.0
        return when {
            estimate > available -> "High OOM risk"
            estimate > available * .75 -> "Tight — lower context"
            else -> "Fits well"
        }
    }

    companion object {
        private val quants = "F32 F16 Q4_0 Q4_1 - - - Q8_0 Q5_0 Q5_1 Q2_K Q3_K_S Q3_K_M Q3_K_L Q4_K_S Q4_K_M Q5_K_S Q5_K_M Q6_K IQ2_XXS IQ2_XS Q2_K_S IQ3_XS IQ3_XXS IQ1_S IQ4_NL IQ3_S IQ3_M IQ2_S IQ2_M IQ4_XS IQ1_M BF16 - - - TQ1_0 TQ2_0 MXFP4 NVFP4 Q1_0 Q2_0 F8_E4M3".split(" ")
        fun read(channel: FileChannel): GgufHeader {
            val size = channel.size()
            require(size >= 24) { "Incomplete GGUF header" }
            return parse(channel.map(FileChannel.MapMode.READ_ONLY, 0, minOf(size, 64L shl 20)), size)
        }

        internal fun parse(input: ByteBuffer, size: Long): GgufHeader {
            val b = input.order(ByteOrder.LITTLE_ENDIAN)
            require(b.int == 0x46554747 && b.int in 2..3) { "Expected GGUF version 2 or 3" }
            b.long // tensor count
            fun count(max: Long) = b.long.also { require(it in 0..max) { "Unsupported GGUF metadata length" } }.toInt()
            fun skip(n: Long) {
                require(n in 0..b.remaining().toLong()) { "GGUF metadata exceeds inspection window" }
                b.position(b.position() + n.toInt())
            }
            fun string(): String {
                val n = count(65536)
                require(n <= b.remaining())
                return ByteArray(n).also { b.get(it) }.decodeToString()
            }
            val widths = intArrayOf(1,1,2,2,4,4,4,1,0,0,8,8,8)
            var arch = ""
            val numbers = mutableMapOf<String, Int>()
            repeat(count(100000)) {
                val key = string()
                when (val type = b.int) {
                    4, 5 -> numbers[key] = b.int
                    8 -> if (key == "general.architecture") arch = string() else skip(b.long)
                    9 -> {
                        val element = b.int
                        val n = count(16_000_000)
                        if (element == 8) repeat(n) { skip(b.long) }
                        else { require(element in widths.indices && widths[element] > 0); skip(n.toLong() * widths[element]) }
                    }
                    else -> { require(type in widths.indices && widths[type] > 0); skip(widths[type].toLong()) }
                }
            }
            fun field(key: String, fallback: Int) = numbers["$arch.$key"]?.takeIf { it in 1..1048576 } ?: fallback
            val kv = field("attention.head_count_kv", field("attention.head_count", 1))
            val dim = field("attention.key_length", field("embedding_length", 4096) / field("attention.head_count", 1))
            val type = numbers["general.file_type"] ?: -1
            return GgufHeader(arch.ifBlank { "Unknown" }, quants.getOrNull(type) ?: "type $type",
                field("block_count", 32), field("context_length", 0), size, (kv.toLong() * dim).coerceIn(1,1048576).toInt())
        }
    }
}
