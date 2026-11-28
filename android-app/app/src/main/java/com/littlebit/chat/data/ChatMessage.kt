package com.littlebit.chat.data

import com.littlebit.chat.engine.ReplyTiming

/**
 * One stored turn. Lives in its own file so the pure JSON/import helpers can be
 * unit-tested without pulling in the Compose-backed [ChatSession].
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val fast: Boolean = false,
    val cutOff: Boolean = false,
    val timing: ReplyTiming? = null,
    val backend: String = "",
    val sources: List<String> = emptyList(),
    val searchQuery: String = "",
)
