package com.littlebit.chat.data

import com.littlebit.chat.engine.ThinkBlocks

/** Fork slicing and Markdown export — pure helpers so both are unit-testable. */
object ChatExport {

    /** Messages a fork copy keeps: everything up to and including [index]. */
    fun forkSlice(messages: List<ChatMessage>, index: Int): List<ChatMessage> =
        if (index !in messages.indices) emptyList() else messages.take(index + 1)

    /** Fork title, capped for the drawer. */
    fun forkTitle(title: String): String = (title.ifBlank { "New chat" } + " (Fork)").take(100)

    /**
     * Clean Markdown for a conversation. Reasoning blocks are stripped so the
     * export contains the answers the user actually read; a cut-off reply keeps
     * a short marker.
     */
    fun markdown(title: String, messages: List<ChatMessage>): String {
        val body = messages.mapNotNull { message ->
            val text = ThinkBlocks.strip(message.content).trim()
            if (text.isEmpty()) return@mapNotNull null
            val who = message.role.replaceFirstChar { it.uppercase() }
            buildString {
                append("## ").append(who)
                if (message.searchQuery.isNotBlank()) append(" · searched \"").append(message.searchQuery).append('"')
                append("\n\n").append(text)
                if (message.cutOff) append("\n\n*(reply ended early)*")
            }
        }.joinToString("\n\n")
        val heading = "# ${title.ifBlank { "Chat" }}".trim()
        return if (body.isEmpty()) heading + "\n" else heading + "\n\n" + body + "\n"
    }
}
