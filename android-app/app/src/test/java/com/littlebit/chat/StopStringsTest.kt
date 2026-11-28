package com.littlebit.chat

import com.littlebit.chat.engine.StopFilter
import org.junit.Assert.*
import org.junit.Test

class StopStringsTest {

    @Test fun passesTextThroughWhenNoStops() {
        val f = StopFilter(emptyList())
        assertEquals("hello world", f.feed("hello world"))
        assertEquals("", f.finish())
    }

    @Test fun ignoresEmptyStops() {
        val f = StopFilter(listOf("", "  ", "stop"))
        assertEquals("say", f.feed("say st"))
        assertEquals(" ", f.feed("op"))
        assertEquals("", f.finish())
        assertTrue(f.matched)
        assertEquals("stop", f.matchedOn)
    }

    @Test fun holdsBackPartialStopAcrossChunks() {
        val f = StopFilter(listOf("</s>"))
        val first = f.feed("answer </")
        val second = f.feed("s>")
        val emitted = first + second + f.finish()
        assertEquals("answer ", emitted)
        assertTrue(f.matched)
        assertEquals("</s>", f.matchedOn)
    }

    @Test fun emitsTextBeforeStop() {
        val f = StopFilter(listOf("</s>"))
        assertEquals("answer ", f.feed("answer </s>trailing"))
        assertEquals("", f.finish())
    }

    @Test fun stopsSortedLongestFirst() {
        val f = StopFilter(listOf("stop", "stopping"))
        // "stopping" is checked first, so the input stops inside it.
        assertEquals("say ", f.feed("say stopping"))
        assertEquals("", f.finish())
    }

    @Test fun deduplicatesStops() {
        val f = StopFilter(listOf("stop", "stop"))
        assertEquals("say ", f.feed("say stop"))
        assertEquals("", f.finish())
    }

    @Test fun onceMatchedNothingFurtherEmitted() {
        val f = StopFilter(listOf("halt"))
        assertEquals("go ", f.feed("go halt"))
        assertEquals("", f.feed("more"))
        assertEquals("", f.finish())
    }

    @Test fun releasesSafeTailBeyondHoldback() {
        val f = StopFilter(listOf("stoppest"))
        // The eight-character input leaves one character beyond the seven-character holdback.
        val released = f.feed("start st")
        val held = f.finish()
        assertEquals("s", released)
        assertEquals("tart st", held)
        assertEquals("start st", released + held)
    }
}
