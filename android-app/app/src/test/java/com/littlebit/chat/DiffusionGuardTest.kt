package com.littlebit.chat

import com.littlebit.chat.data.DiffusionModelGuard
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

class DiffusionGuardTest {

    @Test fun filenamesOfImageModelsAreRejected() {
        assertTrue(DiffusionModelGuard.looksLikeName("stable-diffusion-v1-5-inpainting-Q8_0.gguf"))
        assertTrue(DiffusionModelGuard.looksLikeName("sd-turbo.safetensors"))
        assertTrue(DiffusionModelGuard.looksLikeName("SDXL-base-1.0.safetensors"))
        assertTrue(DiffusionModelGuard.looksLikeName("flux1-dev-Q4.gguf"))
        assertTrue(DiffusionModelGuard.looksLikeName("Qwen-Image-2.1-Turbo-Q2_K.gguf"))
        assertTrue(DiffusionModelGuard.looksLikeName("vae-ft-mse.safetensors"))
        assertFalse(DiffusionModelGuard.looksLikeName("Qwen3-8B-Q4_K_M.gguf"))
        assertFalse(DiffusionModelGuard.looksLikeName("gemma-3-4b-it-Q4_K_M.gguf"))
    }

    @Test fun diffusionTensorNamesAreDetectedInGgufMetadata() {
        val gguf = gguf(
            arch = "qwen3",
            tensors = listOf("model.diffusion_model.input_blocks.0.weight", "blk.0.attn_q.weight"),
        )
        assertTrue(DiffusionModelGuard.looksLikeGguf(ByteArrayInputStream(gguf)))
    }

    @Test fun plainTextModelIsNotFlagged() {
        val gguf = gguf(
            arch = "qwen3",
            keys = listOf("general.name", "qwen3.block_count"),
            tensors = listOf("blk.0.attn_q.weight", "token_embd.weight", "output_norm.weight"),
        )
        assertFalse(DiffusionModelGuard.looksLikeGguf(ByteArrayInputStream(gguf)))
    }

    @Test fun diffusionArchitectureIsDetected() {
        assertTrue(DiffusionModelGuard.looksLikeGguf(ByteArrayInputStream(gguf(arch = "flux"))))
        assertTrue(DiffusionModelGuard.looksLikeGguf(ByteArrayInputStream(gguf(arch = "sdxl"))))
    }

    @Test fun filenameIsCheckedBeforeAnyStreamIsOpened() {
        // A missing file cannot be opened, so `true` proves the name wins first.
        assertTrue(DiffusionModelGuard.detect(File("flux1-dev-Q4_K_M.gguf")))
        assertTrue(DiffusionModelGuard.detect(File("stable-diffusion-v1-5-inpainting.safetensors")))
        assertFalse(DiffusionModelGuard.detect(File("missing-chat-model.gguf")))
    }

    @Test fun shortSafReadsStillScanTheWholeMetadataBlock() {
        val gguf = gguf(
            arch = "qwen3",
            keys = listOf("general.name"),
            tensors = listOf("model.diffusion_model.input_blocks.0.weight"),
        )
        assertTrue(DiffusionModelGuard.looksLikeGguf(DripStream(gguf, chunk = 1)))
    }

    @Test fun zeroByteSafReadStopsCleanlyInsteadOfSpinning() {
        val truncated = gguf(arch = null, keys = listOf("general.name"), tensors = emptyList())
        val cut = truncated.copyOf(truncated.size - 8)
        assertFalse(DiffusionModelGuard.looksLikeGguf(DripStream(cut, chunk = 4, padZeroAtEnd = true)))
    }

    @Test fun garbageNeverThrows() {
        assertFalse(DiffusionModelGuard.looksLikeGguf(ByteArrayInputStream(ByteArray(0))))
        assertFalse(DiffusionModelGuard.looksLikeGguf(ByteArrayInputStream("not a gguf at all".toByteArray())))
        assertFalse(DiffusionModelGuard.looksLikeGguf(ByteArrayInputStream(ByteArray(64) { 7 })))
    }

    /** SAF-like stream: bounded reads, and optionally 0 instead of -1 at EOF. */
    private class DripStream(
        private val data: ByteArray,
        private val chunk: Int,
        private val padZeroAtEnd: Boolean = false,
    ) : InputStream() {
        private var pos = 0

        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return if (padZeroAtEnd) 0 else -1
            val n = minOf(chunk, len, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    private fun gguf(
        arch: String? = null,
        keys: List<String> = emptyList(),
        tensors: List<String> = emptyList(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(value: Int) {
            for (i in 0 until 4) out.write((value ushr (8 * i)) and 0xFF)
        }
        fun u64(value: Long) {
            for (i in 0 until 8) out.write(((value ushr (8 * i)) and 0xFF).toInt())
        }
        fun string(value: String) {
            val bytes = value.toByteArray()
            u64(bytes.size.toLong())
            out.write(bytes)
        }
        val extra = keys.size + if (arch != null) 1 else 0
        u32(0x46554747)
        u32(3)
        u64(tensors.size.toLong())
        u64(extra.toLong())
        if (arch != null) {
            string("general.architecture")
            u32(8)
            string(arch)
        }
        for (key in keys) {
            string(key)
            u32(4) // u32 value
            u32(1)
        }
        for (tensor in tensors) {
            string(tensor)
            u32(1) // one dimension
            u64(1)
            u32(0) // F32
            u64(0) // offset
        }
        return out.toByteArray()
    }
}
