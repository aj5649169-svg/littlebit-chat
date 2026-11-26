package com.littlebit.chat

import com.littlebit.chat.engine.ThinkBlocks
import org.junit.Assert.*
import org.junit.Test

class ThinkBlocksTest {

    @Test fun parsesBothTagForms() {
        val long = ThinkBlocks.split("<thinking>plan</thinking>answer")
        assertEquals("plan", long.think)
        assertEquals("answer", long.answer)

        val short = ThinkBlocks.split("before " + ThinkBlocks.SHORT_OPEN + " plan" + ThinkBlocks.SHORT_CLOSE + " after")
        assertEquals("plan", short.think)
        assertEquals("before  after", short.answer)
    }

    @Test fun closingTagSplitAcrossStreamingChunksNeverLeaks() {
        val chunks = listOf("Answer", " <think", "ing>reason", "ing</thi", "nking> done")
        var raw = ""
        val splits = ArrayList<ThinkBlocks.Split>()
        for (chunk in chunks) {
            raw += chunk
            splits.add(ThinkBlocks.split(raw))
        }
        val last = splits.last()
        assertEquals("reasoning", last.think)
        assertEquals("Answer done", last.answer)
        for (split in splits) assertFalse(split.answer.contains("reasoning"))
    }

    @Test fun cancelledPartialThoughtStaysInReasoning() {
        val split = ThinkBlocks.split("<thinking>half a thought")
        assertEquals("half a thought", split.think)
        assertEquals("", split.answer)

        val cancelled = ThinkBlocks.split("Visible <thinking>unfinished")
        assertEquals("unfinished", cancelled.think)
        assertEquals("Visible ", cancelled.answer)
    }

    @Test fun stripRemovesBothFormsAndUnclosedTail() {
        assertEquals("A B", ThinkBlocks.strip("A " + ThinkBlocks.SHORT_OPEN + " x" + ThinkBlocks.SHORT_CLOSE + " B").replace(Regex("\\s+"), " "))
        assertEquals("A B", ThinkBlocks.strip("A <thinking>x</thinking> B").replace(Regex("\\s+"), " "))
        assertEquals("A", ThinkBlocks.strip("A <thinking>never closed"))
        assertEquals("plain", ThinkBlocks.strip("plain"))
    }

    @Test fun leadingClosedThoughtKeepsEveryAnswerCharacter() {
        val split = ThinkBlocks.split("<thinking>weigh options</thinking>Hello")
        assertEquals("weigh options", split.think)
        assertEquals("Hello", split.answer)
        assertEquals("Hello", ThinkBlocks.strip("<thinking>weigh options</thinking>Hello"))

        val shortForm = ThinkBlocks.split(ThinkBlocks.SHORT_OPEN + "plan" + ThinkBlocks.SHORT_CLOSE + "Hello")
        assertEquals("plan", shortForm.think)
        assertEquals("Hello", shortForm.answer)
    }

    @Test fun leadingUnclosedThoughtKeepsAnswerEmpty() {
        val split = ThinkBlocks.split("<thinking>step 1")
        assertEquals("step 1", split.think)
        assertEquals("", split.answer)

        val shortForm = ThinkBlocks.split(ThinkBlocks.SHORT_OPEN + "step 1")
        assertEquals("step 1", shortForm.think)
        assertEquals("", shortForm.answer)

        // Streaming a leading block one character at a time never leaks reasoning text.
        val raw = "<thinking>step 1"
        for (end in 1..raw.length) {
            val partial = ThinkBlocks.split(raw.substring(0, end))
            assertFalse(partial.answer.contains("step"))
        }
        assertEquals("step 1", ThinkBlocks.split(raw).think)
    }

    @Test fun noThinkAndSentenceGuardsStillHold() {
        assertEquals("hi /no_think", ThinkBlocks.withNoThink("hi", "qwen3"))
        assertEquals("hi", ThinkBlocks.withNoThink("hi", "llama"))
        assertTrue(ThinkBlocks.isSentenceComplete("How are you?"))
        assertFalse(ThinkBlocks.isSentenceComplete("How are"))
    }
}
