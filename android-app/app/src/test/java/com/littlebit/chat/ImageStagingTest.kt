package com.littlebit.chat

import com.littlebit.chat.studio.ImageModelStaging
import com.littlebit.chat.studio.align64
import com.littlebit.chat.studio.validWeight
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ImageStagingTest {

    @Test fun acceptsOnlyLoadableWeightFormats() {
        assertTrue(validWeight("sd15-inpainting.gguf"))
        assertTrue(validWeight("model.SafeTensors"))
        assertFalse(validWeight("model.ckpt"))
        assertFalse(validWeight("model.pth"))
        assertFalse(validWeight("image.png"))
    }

    @Test fun stagedReuseMatchesNameAndExactSize() {
        val dir = Files.createTempDirectory("staging").toFile()
        val file = File(dir, "sd-turbo.gguf").apply { writeBytes(ByteArray(10)) }
        assertEquals(file, ImageModelStaging.findStaged(dir, "sd-turbo.gguf", 10))
        assertNull(ImageModelStaging.findStaged(dir, "sd-turbo.gguf", 11))
        assertNull(ImageModelStaging.findStaged(dir, "other.gguf", 10))
        assertTrue(ImageModelStaging.delete(file))
        assertFalse(file.exists())
    }

    @Test fun namesCannotEscapeTheStagingDirectory() {
        assertEquals("a_b.gguf", ImageModelStaging.safeName("a/b.gguf"))
        assertEquals("a_b.gguf", ImageModelStaging.safeName("a\\b.gguf"))
        assertEquals("model.gguf", ImageModelStaging.safeName("   "))
        assertFalse(ImageModelStaging.safeName("../../etc/passwd").contains("/"))
    }

    @Test fun resolutionAlignsToSixtyFourAndStaysInRange() {
        assertEquals(64, align64(1))
        assertEquals(512, align64(500))
        assertEquals(768, align64(760))
        assertEquals(1024, align64(4000))
    }

    @Test fun byteFormattingIsReadable() {
        assertEquals("10 B", ImageModelStaging.formatBytes(10))
        assertEquals("2 KB", ImageModelStaging.formatBytes(2_000))
        assertEquals("1.50 GB", ImageModelStaging.formatBytes(1_500_000_000))
    }
}
