package com.littlebit.chat.data

import com.littlebit.chat.net.JsonScan

sealed interface ChatImportResult {
    data class Success(val chats: List<ImportedChat>) : ChatImportResult
    data class Failure(val message: String) : ChatImportResult
}

data class ImportedChat(val title: String, val messages: List<ChatMessage>)

/**
 * Imports chat JSON exported by this app (`{"chats":[...]}`) and the common
 * single-chat shape (`{"title":"…","messages":[{"role":"…","content":"…"}]}`).
 *
 * Uses [JsonScan] instead of `org.json` so the parser is unit-testable: the
 * Android `org.json` classes are stubs in local JVM tests. Only the shapes this
 * app produces are supported — this is an import path, not a general parser.
 */
object ChatImport {

    const val MAX_TEXT_CHARS = 16 * 1024 * 1024
    const val MAX_IMPORT_MESSAGES = 400

    private val ROLES = setOf("system", "user", "assistant")

    fun parse(text: String): ChatImportResult {
        if (text.isBlank()) return ChatImportResult.Failure("That file is empty.")
        if (text.length > MAX_TEXT_CHARS) {
            return ChatImportResult.Failure(ChatBudget.overLimitMessage(text.length.toLong()))
        }

        val bodies = arrayBodies(text, "chats").ifEmpty { listOf(text) }
        val chats = ArrayList<ImportedChat>()
        for (body in bodies) {
            val messages = messages(body)
            if (messages.isEmpty()) continue
            val title = JsonScan.stringField(body, "title")
                ?.trim()
                ?.take(100)
                ?.takeIf { it.isNotBlank() }
                ?: defaultTitle(messages)
            chats.add(ImportedChat(title, messages))
        }
        if (chats.isEmpty()) {
            return ChatImportResult.Failure("No chat messages found. Use a Chat JSON export.")
        }
        return ChatImportResult.Success(chats)
    }

    private fun messages(body: String): List<ChatMessage> {
        val roles = JsonScan.allStringValues(body, "role")
        val contents = JsonScan.allStringValues(body, "content")
        if (roles.isEmpty() || contents.isEmpty()) return emptyList()
        val paired = ArrayList<ChatMessage>(minOf(roles.size, contents.size))
        for (index in 0 until minOf(roles.size, contents.size)) {
            val role = roles[index].lowercase()
            if (role !in ROLES) continue
            val content = contents[index]
            if (content.isBlank()) continue
            paired.add(ChatMessage(role, content))
        }
        // Keep the newest turns, matching the in-memory budget.
        return if (paired.size > MAX_IMPORT_MESSAGES) paired.takeLast(MAX_IMPORT_MESSAGES) else paired
    }

    private fun defaultTitle(messages: List<ChatMessage>): String {
        val first = messages.firstOrNull { it.role == "user" }?.content
            ?: messages.firstOrNull()?.content
            ?: return "Imported chat"
        return first.trim().split(Regex("\\s+")).take(6).joinToString(" ").take(80).ifBlank { "Imported chat" }
    }

    /** Bodies of every top-level object inside `"key":[ … ]`. */
    fun arrayBodies(text: String, key: String): List<String> {
        val marker = "\"$key\""
        var from = 0
        while (true) {
            val at = text.indexOf(marker, from)
            if (at < 0) return emptyList()
            var index = at + marker.length
            while (index < text.length && text[index].isWhitespace()) index++
            if (index < text.length && text[index] == ':') {
                index++
                while (index < text.length && text[index].isWhitespace()) index++
                if (index < text.length && text[index] == '[') {
                    val end = matchBracket(text, index) ?: return emptyList()
                    return splitObjects(text.substring(index + 1, end))
                }
            }
            from = at + marker.length
        }
    }

    /** Index of the `]` that closes the `[` at [open], ignoring strings. */
    fun matchBracket(text: String, open: Int): Int? {
        var depth = 0
        var index = open
        var inString = false
        var escaped = false
        while (index < text.length) {
            val c = text[index]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '[' || c == '{' -> depth++
                c == ']' || c == '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return null
    }

    /** Top-level `{…}` objects inside an array body, in document order. */
    fun splitObjects(arrayBody: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (index in arrayBody.indices) {
            val c = arrayBody[index]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                c == '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out.add(arrayBody.substring(start, index + 1))
                        start = -1
                    }
                }
            }
        }
        return out
    }
}
