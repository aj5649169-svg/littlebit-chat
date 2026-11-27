package com.littlebit.chat.studio

import java.io.File

/**
 * Optional Fast VAE (TAESD, `madebyollin/taesd`, MIT, ~9.8 MB for SD 1.5).
 *
 * stable-diffusion.cpp reads it through `sd_ctx_params_t.taesd_path`, so the
 * file has to be a real app-private path: Image Studio stages a SAF pick into
 * [ImageModelStaging.directory] under [FILE_NAME], exactly like image weights.
 * Decoding drops from tens of seconds to roughly 1-3 s with a smaller RAM peak.
 *
 * Pure Kotlin apart from [File]: unit-testable.
 */
object TaesdModel {

    const val FILE_NAME = "taesd.safetensors"
    const val URL = "https://huggingface.co/madebyollin/taesd/resolve/main/taesd.safetensors"
    const val DISPLAY_SIZE = "~9.8 MB"

    /** The real decoder is ~9.79 MB; anything under 1 MB is not a TAESD model. */
    const val MIN_BYTES = 1_000_000L

    fun stagedFile(dir: File): File = File(dir, FILE_NAME)

    fun find(dir: File): File? = stagedFile(dir).takeIf { it.isFile && it.length() >= MIN_BYTES }

    /** Null when [displayName] / [bytes] look like a usable TAESD decoder. */
    fun validate(displayName: String, bytes: Long): String? {
        val name = displayName.lowercase()
        return when {
            !name.endsWith(".safetensors") ->
                "Fast VAE needs a SafeTensors decoder (taesd.safetensors), not a GGUF or checkpoint."
            !name.startsWith("taesd") ->
                "That does not look like taesd.safetensors from madebyollin/taesd."
            bytes < MIN_BYTES ->
                "This file is too small to be a TAESD decoder ($DISPLAY_SIZE expected)."
            else -> null
        }
    }
}
