package com.littlebit.chat

import com.littlebit.chat.studio.ImageStepHint
import com.littlebit.chat.studio.TaesdModel
import org.junit.Assert.*
import org.junit.Test

class ImageStepHintTest {

    @Test fun standardSd15AtOneOrTwoStepsShowsHint() {
        assertEquals(
            ImageStepHint.HINT,
            ImageStepHint.forModel("stable-diffusion-v1-5-inpainting-Q8_0.gguf", steps = 1),
        )
        assertEquals(
            ImageStepHint.HINT,
            ImageStepHint.forModel("stable-diffusion-v1-5-inpainting-Q8_0.gguf", steps = 2),
        )
    }

    @Test fun standardSd15AtFifteenStepsShowsNothing() {
        assertNull(ImageStepHint.forModel("stable-diffusion-v1-5-inpainting-Q8_0.gguf", steps = 15))
    }

    @Test fun turboAndLcmNeverShowTheHint() {
        assertNull(ImageStepHint.forModel("sd-turbo-Q8_0.gguf", steps = 2))
        assertNull(ImageStepHint.forModel("sdxl-lcm.safetensors", steps = 1))
        assertNull(ImageStepHint.forModel("", steps = 1))
    }

    @Test fun taesdValidationAcceptsTheRealDecoderOnly() {
        assertNull(TaesdModel.validate("taesd.safetensors", 9_790_000L))
        assertNull(TaesdModel.validate("taesd_sdxl.safetensors", 9_790_000L))
        assertNotNull(TaesdModel.validate("taesd.safetensors", 12L))
        assertNotNull(TaesdModel.validate("model.gguf", 9_790_000L))
        assertNotNull(TaesdModel.validate("vae.safetensors", 9_790_000L))
        assertTrue(TaesdModel.URL.endsWith("/taesd.safetensors"))
        assertEquals("taesd.safetensors", TaesdModel.FILE_NAME)
    }
}
