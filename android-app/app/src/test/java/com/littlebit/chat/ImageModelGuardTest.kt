package com.littlebit.chat

import com.littlebit.chat.studio.ImageModelGuard
import org.junit.Assert.*
import org.junit.Test

class ImageModelGuardTest {

    @Test fun detectsInpaintingByFilenameAnyCase() {
        assertTrue(ImageModelGuard.isInpainting("sd15-inpainting.gguf"))
        assertTrue(ImageModelGuard.isInpainting("SD15-INPAINTING.GGUF"))
        assertTrue(ImageModelGuard.isInpainting("Inpainting model.gguf"))
        assertTrue(ImageModelGuard.isInpainting("path/to/sd-inpaint-v1.gguf"))
        assertFalse(ImageModelGuard.isInpainting("sd15-turbo.gguf"))
        assertFalse(ImageModelGuard.isInpainting("model.gguf"))
    }

    @Test fun stripsPathSeparators() {
        assertTrue(ImageModelGuard.isInpainting("C:\\Models\\sd-inpaint.gguf"))
        assertTrue(ImageModelGuard.isInpainting("/models/sd-inpaint.gguf"))
    }

    @Test fun requiresPhotoAndMaskOnlyForClassicInpainting() {
        assertTrue(ImageModelGuard.requiresPhotoAndMask("sd-inpaint.gguf", qwen = false))
        assertFalse(ImageModelGuard.requiresPhotoAndMask("sd-inpaint.gguf", qwen = true))
        assertFalse(ImageModelGuard.requiresPhotoAndMask("sd15-turbo.gguf", qwen = false))
    }

    @Test fun canGenerateBlocksInpaintingWithoutPhotoOrMask() {
        assertFalse(ImageModelGuard.canGenerate("sd-inpaint.gguf", qwen = false, hasPhoto = false, hasMask = false))
        assertFalse(ImageModelGuard.canGenerate("sd-inpaint.gguf", qwen = false, hasPhoto = true, hasMask = false))
        assertFalse(ImageModelGuard.canGenerate("sd-inpaint.gguf", qwen = false, hasPhoto = false, hasMask = true))
        assertTrue(ImageModelGuard.canGenerate("sd-inpaint.gguf", qwen = false, hasPhoto = true, hasMask = true))
    }

    @Test fun canGenerateAllowsInpaintingForQwenEdit() {
        assertTrue(ImageModelGuard.canGenerate("sd-inpaint.gguf", qwen = true, hasPhoto = false, hasMask = false))
    }

    @Test fun canGenerateRejectsBlankModel() {
        assertFalse(ImageModelGuard.canGenerate("", qwen = false, hasPhoto = true, hasMask = true))
        assertFalse(ImageModelGuard.canGenerate("   ", qwen = false, hasPhoto = true, hasMask = true))
    }

    @Test fun canGenerateAllowsNonInpaintingWithoutMedia() {
        assertTrue(ImageModelGuard.canGenerate("sd15-turbo.gguf", qwen = false, hasPhoto = false, hasMask = false))
    }
}
