package com.littlebit.chat

import com.littlebit.chat.data.DiffusionModelGuard
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

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

    @Test fun garbageNeverThrows() {
        assertFalse(DiffusionModelGuard.looksLikeGguf(ByteArrayInputStream(ByteArray(0))))
        assertFalse(DiffusionModelGuard.looksLikeGguf(ByteArrayInputStream("not a gguf at all".toByteArray())))
        assertFalse(DiffusionModelGuard.looksLikeGguf(ByteArrayInputStream(ByteArray(64) { 7 })))
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
