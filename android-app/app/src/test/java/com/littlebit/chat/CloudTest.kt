package com.littlebit.chat

import com.littlebit.chat.net.CloudEndpointValidator
import com.littlebit.chat.net.JsonScan
import com.littlebit.chat.net.OpenAiDelta
import com.littlebit.chat.net.SseParser
import org.junit.Assert.*
import org.junit.Test

class CloudTest {

    @Test fun sseHandlesCommentsBlankLinesAndBothDataSpellings() {
        val events = SseParser.LineBuffer().feed(
            ": OPENROUTER PROCESSING\n\n" +
                "data: {\"a\":1}\n\n" +
                "data:{\"b\":2}\r\n\r\n" +
                "event: ping\n" +
                "data: [DONE]\n\n",
        )
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}", "[DONE]"), events)
        assertTrue(SseParser.isDone(events.last()))
        assertFalse(SseParser.isDone("{\"a\":1}"))
    }

    @Test fun sseReassemblesPayloadsSplitAcrossNetworkChunks() {
        val buffer = SseParser.LineBuffer()
        val events = ArrayList<String>()
        events += buffer.feed("data: {\"choices\":[{\"delta\":{\"con")
        events += buffer.feed("tent\":\"hel")
        events += buffer.feed("lo\"}}]}\n\n")
        assertEquals(listOf("{\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}"), events)
    }

    @Test fun sseJoinsMultilineDataAndFlushesAtEof() {
        val buffer = SseParser.LineBuffer()
        assertEquals(emptyList<String>(), buffer.feed("data: line1\ndata: line2\n"))
        assertEquals(listOf("line1\nline2"), buffer.feed("\n"))
        val tail = SseParser.LineBuffer()
        assertEquals(emptyList<String>(), tail.feed("data: last"))
        assertEquals(listOf("last"), tail.flush())
    }

    @Test fun jsonScanReadsEscapedFields() {
        val json = """{"content":"he said \"hi\"\nnext","n":42,"ok":true,"id":"a"}"""
        assertEquals("he said \"hi\"\nnext", JsonScan.stringField(json, "content"))
        assertEquals(42.0, JsonScan.numberField(json, "n")!!, 0.0)
        assertEquals(42, JsonScan.intField(json, "n"))
        assertTrue(JsonScan.booleanField(json, "ok")!!)
        assertEquals("a", JsonScan.stringField(json, "id"))
        assertNull(JsonScan.stringField(json, "missing"))
    }

    @Test fun deltaParsesContentReasoningAndErrors() {
        assertEquals("answer", OpenAiDelta.parse("""{"choices":[{"delta":{"content":"answer"}}]}""").content)
        assertEquals("think", OpenAiDelta.parse("""{"choices":[{"delta":{"reasoning_content":"think"}}]}""").reasoning)
        assertEquals("think2", OpenAiDelta.parse("""{"delta":{"reasoning":"think2"}}""").reasoning)
        assertEquals("bad key", OpenAiDelta.parse("""{"error":{"message":"bad key"}}""").error)
        assertEquals("", OpenAiDelta.parse("""{"choices":[{"delta":{"role":"assistant"}}]}""").content)
    }

    @Test fun modelListIsExtractedFromIds() {
        val body = """{"data":[{"id":"z-model"},{"id":"a-model"},{"id":"z-model"}]}"""
        assertEquals(listOf("z-model", "a-model", "z-model"), JsonScan.allStringValues(body, "id"))
    }

    @Test fun endpointPolicyAllowsTlsAndPrivateCleartextOnly() {
        assertTrue(CloudEndpointValidator.isAllowed("https://api.openai.com/v1"))
        assertTrue(CloudEndpointValidator.isAllowed("https://openrouter.ai/api/v1/"))
        assertTrue(CloudEndpointValidator.isAllowed("http://127.0.0.1:11434/v1"))
        assertTrue(CloudEndpointValidator.isAllowed("http://localhost:11434/v1"))
        assertTrue(CloudEndpointValidator.isAllowed("http://192.168.1.20:8080/v1"))
        assertTrue(CloudEndpointValidator.isAllowed("http://10.0.0.5:1234/v1"))
        assertTrue(CloudEndpointValidator.isAllowed("http://172.20.0.2:11434/v1"))
        assertFalse(CloudEndpointValidator.isAllowed("http://api.openai.com/v1"))
        assertFalse(CloudEndpointValidator.isAllowed("http://8.8.8.8/v1"))
        assertFalse(CloudEndpointValidator.isAllowed("ftp://example.com"))
        assertFalse(CloudEndpointValidator.isAllowed("https://user:pass@example.com/v1"))
        assertFalse(CloudEndpointValidator.isAllowed("https://example.com/v1?x=1"))
        assertFalse(CloudEndpointValidator.isAllowed(""))
        assertFalse(CloudEndpointValidator.isAllowed("not a url"))
    }

    @Test fun urlsAreBuiltFromTheValidatedBase() {
        assertEquals(
            "https://api.groq.com/openai/v1/chat/completions",
            CloudEndpointValidator.chatCompletions("https://api.groq.com/openai/v1/"),
        )
        assertEquals(
            "http://127.0.0.1:11434/v1/models",
            CloudEndpointValidator.models("http://127.0.0.1:11434/v1"),
        )
        assertNull(CloudEndpointValidator.chatCompletions("http://example.com/v1"))
        // A pasted full endpoint still resolves to the same URL.
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            CloudEndpointValidator.chatCompletions("https://api.openai.com/v1/chat/completions"),
        )
        assertEquals(
            "https://api.openai.com/v1/models",
            CloudEndpointValidator.models("https://api.openai.com/v1/chat/completions"),
        )
    }
}
