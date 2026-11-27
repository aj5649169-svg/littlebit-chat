package com.littlebit.chat.data

/**
 * Streaming encoder for `chats.json`.
 *
 * The previous save path built the whole history as one `JSONObject` tree and
 * then one `String`, doubling peak heap on a long chat. This writes a single
 * message at a time into a `Writer`, so peak heap is one message plus the
 * writer buffer.
 *
 * Uses [com.littlebit.chat.net.JsonScan.quote] rather than `org.json`, because
 * `org.json` is a stub in local JVM unit tests.
 */
object ChatJson {

    const val SCHEMA_VERSION = 2

    fun writeHeader(out: Appendable) {
        out.append("{\"schema\":").append(SCHEMA_VERSION.toString()).append(",\"chats\":[")
    }

    fun writeFooter(out: Appendable) {
        out.append("]}")
    }

    fun writeSeparator(out: Appendable, first: Boolean) {
        if (!first) out.append(',')
    }

    fun writeSession(
        out: Appendable,
        id: String,
        title: String,
        updatedAt: Long,
        pinned: Boolean,
        trimmed: Int,
        messages: List<ChatMessage>,
    ) {
        out.append('{')
        out.append("\"id\":").append(com.littlebit.chat.net.JsonScan.quote(id))
        out.append(",\"title\":").append(com.littlebit.chat.net.JsonScan.quote(title))
        out.append(",\"updatedAt\":").append(updatedAt.toString())
        out.append(",\"pinned\":").append(if (pinned) "true" else "false")
        if (trimmed > 0) out.append(",\"trimmed\":").append(trimmed.toString())
        out.append(",\"messages\":[")
        messages.forEachIndexed { index, message ->
            if (index > 0) out.append(',')
            writeMessage(out, message)
        }
        out.append("]}")
    }

    fun writeMessage(out: Appendable, message: ChatMessage) {
        out.append('{')
        if (message.sources.isNotEmpty()) {
            out.append("\"sources\":[")
            message.sources.forEachIndexed { index, link ->
                if (index > 0) out.append(',')
                out.append(com.littlebit.chat.net.JsonScan.quote(link))
            }
            out.append("],")
        }
        if (message.searchQuery.isNotEmpty()) {
            out.append("\"searchQuery\":").append(com.littlebit.chat.net.JsonScan.quote(message.searchQuery)).append(',')
        }
        out.append("\"fast\":").append(if (message.fast) "true" else "false")
        out.append(",\"cutOff\":").append(if (message.cutOff) "true" else "false")
        out.append(",\"role\":").append(com.littlebit.chat.net.JsonScan.quote(message.role))
        out.append(",\"content\":").append(com.littlebit.chat.net.JsonScan.quote(message.content))
        message.timing?.let { timing ->
            out.append(",\"backend\":").append(com.littlebit.chat.net.JsonScan.quote(message.backend))
            out.append(",\"timing\":{\"promptTokens\":").append(timing.promptTokens.toString())
                .append(",\"promptMs\":").append(timing.promptMs.toString())
                .append(",\"tokens\":").append(timing.tokens.toString())
                .append(",\"genMs\":").append(timing.genMs.toString())
                .append(",\"firstMs\":").append(timing.firstMs.toString())
                .append(",\"used\":").append(timing.used.toString())
                .append(",\"capacity\":").append(timing.capacity.toString())
                .append('}')
        }
        out.append('}')
    }

    /** Whole-document encoding, used by tests and the import round trip. */
    fun encode(sessions: List<EncodedSession>): String = buildString {
        writeHeader(this)
        sessions.forEachIndexed { index, session ->
            writeSeparator(this, index == 0)
            writeSession(this, session.id, session.title, session.updatedAt, session.pinned, session.trimmed, session.messages)
        }
        writeFooter(this)
    }

    data class EncodedSession(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val pinned: Boolean = false,
        val trimmed: Int = 0,
        val messages: List<ChatMessage>,
    )
}
