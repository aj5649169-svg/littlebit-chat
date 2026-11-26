package com.littlebit.chat.data

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Image Studio models are GGUF/safetensors files too. Handing one to llama.cpp
 * aborts the load at best and allocates a nonsense graph at worst, so Model Hub
 * checks the filename and (for GGUF) the metadata keys and tensor names before
 * a load or a private copy is offered.
 *
 * Inspection only reads through `contentResolver.openInputStream(uri)`; it never
 * opens `/proc/self/fd/<N>` by path.
 */
object DiffusionModelGuard {

    private val NAME_MARKERS = listOf(
        "stable-diffusion", "stable_diffusion", "sd15", "sd-turbo", "sd_turbo", "sdxl",
        "flux", "qwen-image", "qwen_image", "inpainting", "unet", "vae", "diffusion",
    )

    private val ARCHES = setOf(
        "flux", "stable-diffusion", "stable_diffusion", "sdxl", "sd1", "sd2", "qwen_image",
    )

    private val KEY_MARKERS = listOf(
        "model.diffusion_model", "first_stage_model", "cond_stage_model", "diffusion_model",
        "text_encoder.", "text_encoders.", "vae.", "unet.", "conditioner.", "qwen_image",
    )

    fun looksLikeName(name: String): Boolean {
        val lower = name.lowercase()
        return NAME_MARKERS.any { lower.contains(it) }
    }

    /** Best-effort guard: a parse failure means "not detected", never a crash. */
    fun detect(context: Context, uri: Uri, name: String): Boolean {
        if (looksLikeName(name)) return true
        if (!name.endsWith(".gguf", true)) return false
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { looksLikeGguf(it) } ?: false
        }.getOrDefault(false)
    }

    fun detect(file: File): Boolean {
        if (looksLikeName(file.name)) return true
        if (!file.isFile || !file.name.endsWith(".gguf", true)) return false
        return runCatching { FileInputStream(file).use { looksLikeGguf(it) } }.getOrDefault(false)
    }

    fun looksLikeGguf(input: InputStream): Boolean {
        return try {
            val scan = GgufScan.read(BufferedInputStream(input, 256 * 1024))
            val arch = scan.strings["general.architecture"].orEmpty().lowercase()
            if (arch in ARCHES) return true
            val keys = scan.keys
            val tensorHit = scan.tensors.any { tensor -> KEY_MARKERS.any { marker -> tensor.contains(marker, ignoreCase = true) } }
            val keyHit = keys.any { key -> KEY_MARKERS.any { marker -> key.contains(marker, ignoreCase = true) } }
            keyHit || tensorHit
        } catch (e: Throwable) {
            false
        }
    }

    /** Minimal sequential GGUF metadata/tensor-name reader with a byte budget. */
    internal class GgufScan(
        val strings: Map<String, String>,
        val keys: Set<String>,
        val tensors: Set<String>,
    ) {
        companion object {
            private const val MAX_KEYS = 300_000
            private const val MAX_TENSORS = 4_000
            private const val MAX_STRING = 1 shl 20
            private const val BUDGET = 48L * 1024 * 1024
            private val WIDTHS = intArrayOf(1, 1, 2, 2, 4, 4, 4, 1, 0, 0, 8, 8, 8)
            private val WANTED = setOf("general.architecture", "general.name")

            fun read(input: InputStream): GgufScan {
                val src = LeInput(input, BUDGET)
                require(src.u32() == 0x46554747L) { "Not a GGUF file" }
                val version = src.u32()
                require(version in 2L..3L) { "Unsupported GGUF version" }
                val tensorCount = src.u64()
                val kvCount = src.u64()
                require(kvCount in 0L..MAX_KEYS.toLong()) { "Unsupported metadata count" }

                val strings = HashMap<String, String>()
                val keys = HashSet<String>()
                for (i in 0 until kvCount.toInt()) {
                    val key = src.string()
                    keys.add(key)
                    when (val type = src.u32().toInt()) {
                        8 -> {
                            val value = src.string()
                            if (key in WANTED) strings[key] = value
                        }
                        9 -> {
                            val element = src.u32().toInt()
                            val count = src.u64()
                            src.skipArray(element, count)
                        }
                        in GgufScan.WIDTHS.indices -> {
                            val width = GgufScan.WIDTHS[type]
                            require(width > 0) { "Unsupported metadata type $type" }
                            src.skip(width.toLong())
                        }
                        else -> throw IllegalStateException("Unsupported metadata type $type")
                    }
                }

                val tensors = HashSet<String>()
                val limit = minOf(tensorCount, MAX_TENSORS.toLong()).toInt()
                for (i in 0 until limit) {
                    tensors.add(src.string())
                    val dims = src.u32().toInt()
                    require(dims in 0..8) { "Unsupported tensor rank" }
                    repeat(dims) { src.u64() }
                    src.u32()
                    src.u64()
                }
                return GgufScan(strings, keys, tensors)
            }
        }

        private class LeInput(private val source: InputStream, private val budget: Long) {
            var used = 0L
                private set

            fun u32(): Long {
                var value = 0L
                for (i in 0 until 4) value = value or (byte().toLong() shl (8 * i))
                return value
            }

            fun u64(): Long {
                var value = 0L
                for (i in 0 until 8) value = value or (byte().toLong() shl (8 * i))
                return value
            }

            fun string(): String {
                val length = u64()
                require(length in 0L..MAX_STRING.toLong()) { "Unsupported GGUF string length" }
                val bytes = ByteArray(length.toInt())
                readFully(bytes, bytes.size)
                return String(bytes, Charsets.UTF_8)
            }

            fun skip(count: Long) {
                require(count >= 0) { "Negative skip" }
                require(used + count <= budget) { "GGUF metadata exceeds inspection budget" }
                var left = count
                val scratch = ByteArray(8192)
                while (left > 0) {
                    val read = source.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
                    if (read < 0) throw EOFException()
                    left -= read
                    used += read
                }
            }

            fun skipArray(element: Int, count: Long) {
                require(count in 0L..50_000_000L) { "Unsupported array length" }
                if (element == 8) {
                    repeat(count.toInt()) { string() }
                    return
                }
                require(element in GgufScan.WIDTHS.indices && GgufScan.WIDTHS[element] > 0) { "Unsupported array type $element" }
                skip(count * GgufScan.WIDTHS[element])
            }

            private fun byte(): Int {
                val one = ByteArray(1)
                readFully(one, 1)
                return one[0].toInt() and 0xFF
            }

            private fun readFully(target: ByteArray, length: Int) {
                var offset = 0
                while (offset < length) {
                    val read = source.read(target, offset, length - offset)
                    if (read < 0) throw EOFException()
                    offset += read
                    used += read
                    require(used <= budget) { "GGUF metadata exceeds inspection budget" }
                }
            }
        }
    }
}
