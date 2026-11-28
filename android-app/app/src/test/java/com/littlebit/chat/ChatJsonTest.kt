package com.littlebit.chat

import com.littlebit.chat.data.ChatJson
import com.littlebit.chat.data.ChatMessage
import com.littlebit.chat.net.JsonScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatJsonTest {

    private fun encode(vararg sessions: ChatJson.EncodedSession): String = ChatJson.encode(sessions.toList())

    @Test
    fun `every chat and message survives the round trip`() {
        val json = encode(
            ChatJson.EncodedSession(
                id = "chat-a",
                title = "First",
                updatedAt = 1_700_000_000_000,
                messages = listOf(
                    ChatMessage("user", "hi"),
                    ChatMessage("assistant", "hello \"there\"\nnext\tline"),
                ),
            ),
            ChatJson.EncodedSession(
                id = "chat-b",
                title = "Second",
                updatedAt = 1_700_000_001_000,
                messages = listOf(ChatMessage("user", "second chat")),
            ),
        )
        assertTrue(json.startsWith("{\"schema\":${ChatJson.SCHEMA_VERSION},\"chats\":["))
        assertEquals(listOf("First", "Second"), JsonScan.allStringValues(json, "title"))
        assertEquals(listOf("chat-a", "chat-b"), JsonScan.allStringValues(json, "id"))
        assertEquals(listOf("user", "assistant", "user"), JsonScan.allStringValues(json, "role"))
        assertEquals(
            listOf("hi", "hello \"there\"\nnext\tline", "second chat"),
            JsonScan.allStringValues(json, "content"),
        )
    }

    @Test
    fun `empty history is still valid JSON`() {
        assertEquals("{\"schema\":2,\"chats\":[]}", ChatJson.encode(emptyList()))
    }

    @Test
    fun `trimmed count is written only when messages were dropped`() {
        val kept = encode(ChatJson.EncodedSession("id", "t", 0L, messages = listOf(ChatMessage("user", "x"))))
        val trimmed = encode(
            ChatJson.EncodedSession("id", "t", 0L, trimmed = 7, messages = listOf(ChatMessage("user", "x"))),
        )
        assertFalse(kept.contains("\"trimmed\""))
        assertTrue(trimmed.contains("\"trimmed\":7"))
    }

    @Test
    fun `sources and search query are preserved`() {
        val json = encode(
            ChatJson.EncodedSession(
                "id", "t", 0L,
                messages = listOf(
                    ChatMessage("assistant", "answer", sources = listOf("https://a.example", "https://b.example"),
                        searchQuery = "weather"),
                ),
            ),
        )
        assertTrue(json.contains("\"sources\":[\"https://a.example\",\"https://b.example\"]"))
        assertEquals("weather", JsonScan.stringField(json, "searchQuery"))
    }

    @Test
    fun `control characters are escaped so the document stays parseable`() {
        val json = encode(
            ChatJson.EncodedSession("id", "t", 0L, messages = listOf(ChatMessage("user", "bell\u0007"))) ,
        )
        assertTrue(json.contains("\\u0007"))
        assertFalse(json.contains("\u0007"))
    }
}
