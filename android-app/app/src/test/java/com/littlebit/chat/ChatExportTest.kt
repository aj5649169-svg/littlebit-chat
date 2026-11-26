package com.littlebit.chat

import com.littlebit.chat.data.ChatExport
import com.littlebit.chat.data.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class ChatExportTest {

    private val history = listOf(
        ChatMessage("user", "What is the tallest building?"),
        ChatMessage("assistant", "Burj Khalifa, 828 m."),
        ChatMessage("user", "And the second?"),
        ChatMessage("assistant", "Merdeka 118, 679 m."),
    )

    @Test fun forkKeepsHistoryThroughTheChosenReply() {
        val slice = ChatExport.forkSlice(history, 1)
        assertEquals(2, slice.size)
        assertEquals("Burj Khalifa, 828 m.", slice.last().content)
    }

    @Test fun forkFromFirstAndLastMessage() {
        assertEquals(1, ChatExport.forkSlice(history, 0).size)
        assertEquals(history.size, ChatExport.forkSlice(history, history.lastIndex).size)
    }

    @Test fun forkIgnoresOutOfRangeIndexes() {
        assertTrue(ChatExport.forkSlice(history, -1).isEmpty())
        assertTrue(ChatExport.forkSlice(history, 99).isEmpty())
        assertTrue(ChatExport.forkSlice(emptyList(), 0).isEmpty())
    }

    @Test fun forkTitleIsCappedAndLabelled() {
        assertEquals("Trip plan (Fork)", ChatExport.forkTitle("Trip plan"))
        assertEquals("New chat (Fork)", ChatExport.forkTitle("  "))
        assertTrue(ChatExport.forkTitle("x".repeat(200)).length <= 100)
    }

    @Test fun markdownStripsReasoningAndKeepsAnswers() {
        val messages = listOf(
            ChatMessage("user", "Hi"),
            ChatMessage("assistant", " thinkingsecret reasoning</think>Hello!"),
        )
        val md = ChatExport.markdown("Greeting", messages)
        assertTrue(md.startsWith("# Greeting"))
        assertTrue(md.contains("## User"))
        assertTrue(md.contains("Hello!"))
        assertFalse(md.contains("secret reasoning"))
        assertFalse(md.contains("thinking"))
    }

    @Test fun markdownMarksCutOffRepliesAndSearches() {
        val messages = listOf(
            ChatMessage("assistant", "Partial answer", cutOff = true, searchQuery = "weather in Varanasi"),
        )
        val md = ChatExport.markdown("Chat", messages)
        assertTrue(md.contains("searched \"weather in Varanasi\""))
        assertTrue(md.contains("reply ended early"))
    }

    @Test fun emptyConversationStillExportsAHeading() {
        assertEquals("# Chat\n", ChatExport.markdown("Chat", emptyList()))
        assertEquals("# Chat\n", ChatExport.markdown("", listOf(ChatMessage("assistant", "   "))))
    }
}
