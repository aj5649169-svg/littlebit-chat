package com.littlebit.chat

import com.littlebit.chat.data.ChatBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBudgetTest {

    @Test
    fun `a file at the limit is still readable`() {
        assertFalse(ChatBudget.isOversized(ChatBudget.MAX_FILE_BYTES))
    }

    @Test
    fun `a file one byte over the limit is refused`() {
        assertTrue(ChatBudget.isOversized(ChatBudget.MAX_FILE_BYTES + 1))
    }

    @Test
    fun `short chats are kept whole`() {
        assertEquals(12, ChatBudget.retainedCount(12))
        assertEquals(0, ChatBudget.trimmedCount(12))
    }

    @Test
    fun `long chats keep the newest messages`() {
        val total = ChatBudget.MAX_MESSAGES_PER_CHAT + 40
        assertEquals(ChatBudget.MAX_MESSAGES_PER_CHAT, ChatBudget.retainedCount(total))
        assertEquals(40, ChatBudget.trimmedCount(total))
    }

    @Test
    fun `empty and negative counts never underflow`() {
        assertEquals(0, ChatBudget.retainedCount(0))
        assertEquals(0, ChatBudget.trimmedCount(-5))
    }

    @Test
    fun `over limit message names the limit and the action`() {
        val message = ChatBudget.overLimitMessage(ChatBudget.MAX_FILE_BYTES * 2)
        assertTrue(message.contains("16 MB"))
        assertTrue(message.contains(ChatBudget.OVER_LIMIT))
    }

    @Test
    fun `trimmed notice is singular for one message`() {
        assertTrue(ChatBudget.trimmedNotice(1).contains("1 earlier message trimmed"))
        assertTrue(ChatBudget.trimmedNotice(3).contains("3 earlier messages trimmed"))
    }
}
