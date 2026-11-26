package com.littlebit.chat

import com.littlebit.chat.data.ModelsCatalog
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ImageHubTest {
    private val json: String by lazy {
        val candidates = listOf(File("src/main/assets/models.json"), File("app/src/main/assets/models.json"))
        candidates.first { it.isFile }.readText()
    }

    @Test fun imageTierIsPartOfTheHubOrder() {
        assertTrue("Image" in ModelsCatalog.TIER_ORDER)
        assertTrue(json.contains("\"tier\": \"Image\""))
    }

    @Test fun imageSectionListsVerifiedModels() {
        assertTrue(json.contains("SD 1.5 Inpainting"))
        assertTrue(json.contains("SD-Turbo"))
        assertTrue(json.contains("Qwen-Image-2.1-Turbo (2-Bit)"))
        assertTrue(json.contains("stable-diffusion-v1-5/stable-diffusion-inpainting"))
        assertTrue(json.contains("stabilityai/sd-turbo"))
        assertTrue(json.contains("AtomicChat/Qwen-Image-2.1-Turbo-GGUF"))
    }

    @Test fun twoBitQwenImageCarriesAnHonestLowRamWarning() {
        val start = json.indexOf("qwen-image-2.1-turbo-2bit")
        assertTrue(start > 0)
        val entry = json.substring(start, minOf(json.length, start + 1600))
        assertTrue(entry.contains("AD-Q2_K"))
        assertTrue(entry.contains("Experimental"))
        assertTrue(entry.contains("8 GB+ RAM recommended"))
        assertTrue(entry.contains("\"minRamGb\": 8"))
        assertTrue(entry.contains("\"recommendedRamGb\": 12"))
    }
}
