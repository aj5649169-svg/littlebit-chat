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
        assertEquals("say ", f.feed("say st"))
        assertEquals("op", f.feed("op"))
        assertEquals("", f.finish())
    }

    @Test fun holdsBackPartialStopAcrossChunks() {
        val f = StopFilter(listOf("</s>"))
        assertEquals("", f.feed("answer </"))
        assertEquals("", f.feed("s>"))
        assertEquals("answer ", f.finish())
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
        // holdback keeps longest-1 = 7 chars; "start " is 6 chars -> released.
        assertEquals("start ", f.feed("start st"))
        assertEquals("", f.finish())
    }
}
