package com.littlebit.chat

import com.littlebit.chat.engine.TtsTextSanitizer
import org.junit.Assert.*
import org.junit.Test

class TtsTest {

    @Test fun sanitizerRemovesReasoningCodeAndMarkdown() {
        val raw = """
            <thinking>secret plan</thinking>
            # Heading
            **Bold** and _italic_ with `inlineCode`.
            ```kotlin
            val hidden = true
            ```
            - bullet one
            - bullet two
            [link](https://example.com) and ![image](https://example.com/a.png)
        """.trimIndent()
        val clean = TtsTextSanitizer.sanitize(raw)
        assertFalse(clean.contains("secret plan"))
        assertFalse(clean.contains("hidden"))
        assertFalse(clean.contains("```"))
        assertFalse(clean.contains("**"))
        assertFalse(clean.contains("https://example.com/a.png"))
        assertFalse(clean.contains("# Heading"))
        assertTrue(clean.contains("Heading"))
        assertTrue(clean.contains("Bold"))
        assertTrue(clean.contains("bullet one"))
        assertTrue(clean.contains("link"))
    }

    @Test fun unclosedThoughtIsNarrationFree() {
        val clean = TtsTextSanitizer.sanitize("Answer text <thinking>still going")
        assertEquals("Answer text", clean)
    }

    @Test fun chunksRespectThePlatformLimit() {
        val text = (1..400).joinToString(" ") { "Sentence number $it ends here." }
        val chunks = TtsTextSanitizer.chunks(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= TtsTextSanitizer.MAX_CHUNK })
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test fun oneHugeSentenceIsHardSplit() {
        val huge = "word ".repeat(1500).trim()
        val chunks = TtsTextSanitizer.chunks(huge, max = 3000)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.length <= 3000 })
        assertTrue(chunks.joinToString(" ").contains("word word"))
    }

    @Test fun blankInputHasNoChunks() {
        assertTrue(TtsTextSanitizer.chunks("   ").isEmpty())
        assertEquals("", TtsTextSanitizer.sanitize(""))
    }
}
