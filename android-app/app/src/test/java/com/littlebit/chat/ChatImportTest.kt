package com.littlebit.chat

import com.littlebit.chat.data.ChatImport
import com.littlebit.chat.data.ChatImportResult
import com.littlebit.chat.data.ChatJson
import com.littlebit.chat.data.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImportTest {

    private fun success(text: String): ChatImportResult.Success {
        val result = ChatImport.parse(text)
        assertTrue("expected success, got $result", result is ChatImportResult.Success)
        return result as ChatImportResult.Success
    }

    @Test
    fun `our own export imports back unchanged`() {
        val json = ChatJson.encode(
            listOf(
                ChatJson.EncodedSession(
                    id = "a", title = "Trip", updatedAt = 5L,
                    messages = listOf(
                        ChatMessage("user", "plan a trip to Kyoto"),
                        ChatMessage("assistant", "Start with Fushimi Inari at dawn."),
                    ),
                ),
                ChatJson.EncodedSession(
                    id = "b", title = "Code", updatedAt = 6L,
                    messages = listOf(ChatMessage("user", "explain quicksort")),
                ),
            ),
        )
        val chats = success(json).chats
        assertEquals(2, chats.size)
        assertEquals("Trip", chats[0].title)
        assertEquals(2, chats[0].messages.size)
        assertEquals("plan a trip to Kyoto", chats[0].messages[0].content)
        assertEquals("assistant", chats[0].messages[1].role)
        assertEquals("Code", chats[1].title)
    }

    @Test
    fun `a bare single chat object is accepted`() {
        val chats = success(
            """{"title":"Solo","messages":[{"role":"user","content":"hi"},{"role":"assistant","content":"yo"}]}""",
        ).chats
        assertEquals(1, chats.size)
        assertEquals("Solo", chats[0].title)
        assertEquals(listOf("hi", "yo"), chats[0].messages.map { it.content })
    }

    @Test
    fun `a missing title falls back to the first user message`() {
        val chats = success(
            """{"messages":[{"role":"user","content":"what is the tallest building"},{"role":"assistant","content":"Burj Khalifa"}]}""",
        ).chats
        assertEquals("what is the tallest building", chats[0].title)
    }

    @Test
    fun `empty and message-free documents fail cleanly`() {
        assertTrue(ChatImport.parse("   ") is ChatImportResult.Failure)
        assertTrue(ChatImport.parse("""{"chats":[]}""") is ChatImportResult.Failure)
        assertTrue(ChatImport.parse("""{"title":"nothing"}""") is ChatImportResult.Failure)
    }

    @Test
    fun `brackets inside strings do not break object splitting`() {
        val text = """{"chats":[{"id":"a","note":"} ] {"},{"id":"b"}]}"""
        val bodies = ChatImport.arrayBodies(text, "chats")
        assertEquals(2, bodies.size)
        assertTrue(bodies[0].contains("} ] {"))
        assertTrue(bodies[1].contains("\"b\""))
    }

    @Test
    fun `unknown roles are dropped and blank content is skipped`() {
        val chats = success(
            """{"messages":[{"role":"tool","content":"ignore me"},{"role":"user","content":"keep"},{"role":"assistant","content":"  "}]}""",
        ).chats
        assertEquals(1, chats[0].messages.size)
        assertEquals("keep", chats[0].messages[0].content)
    }

    @Test
    fun `oversized imports are capped to the newest messages`() {
        val messages = (0 until ChatImport.MAX_IMPORT_MESSAGES + 25).joinToString(",") { index ->
            """{"role":"user","content":"m$index"}"""
        }
        val chats = success("""{"messages":[$messages]}""").chats
        assertEquals(ChatImport.MAX_IMPORT_MESSAGES, chats[0].messages.size)
        assertEquals("m25", chats[0].messages.first().content)
    }
}
