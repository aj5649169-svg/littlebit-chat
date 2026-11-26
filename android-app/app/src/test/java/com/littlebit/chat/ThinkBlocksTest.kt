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

    @Test fun noThinkAndSentenceGuardsStillHold() {
        assertEquals("hi /no_think", ThinkBlocks.withNoThink("hi", "qwen3"))
        assertEquals("hi", ThinkBlocks.withNoThink("hi", "llama"))
        assertTrue(ThinkBlocks.isSentenceComplete("How are you?"))
        assertFalse(ThinkBlocks.isSentenceComplete("How are"))
    }
}
