package com.littlebit.chat.studio

/**
 * Client-side mirror of the native inpainting guard in `sd_jni.cpp`.
 *
 * An `-inpainting` checkpoint has no unconditional generation path: without a
 * photo and a mask it loads every weight, then stalls in the encoder. The
 * native guard is the authority (it also protects against a stale UI), and this
 * object exists so the Generate button can explain the requirement *before* the
 * user waits for a 2 GB load.
 *
 * Pure Kotlin: unit-testable on the JVM.
 */
object ImageModelGuard {

    const val INPAINTING_MESSAGE = "Inpainting checkpoints need a photo and a painted mask."

    /** True when the file name (any path separator, any case) says "inpaint". */
    fun isInpainting(modelName: String): Boolean {
        val name = modelName.substringAfterLast('/').substringAfterLast('\\')
        return name.contains("inpaint", ignoreCase = true)
    }

    /** Only classic (non-Qwen-edit) inpainting checkpoints require photo + mask. */
    fun requiresPhotoAndMask(modelName: String, qwen: Boolean): Boolean =
        !qwen && isInpainting(modelName)

    /** Whether Generate may be pressed at all. */
    fun canGenerate(modelName: String, qwen: Boolean, hasPhoto: Boolean, hasMask: Boolean): Boolean {
        if (modelName.isBlank()) return false
        if (!requiresPhotoAndMask(modelName, qwen)) return true
        return hasPhoto && hasMask
    }
}
