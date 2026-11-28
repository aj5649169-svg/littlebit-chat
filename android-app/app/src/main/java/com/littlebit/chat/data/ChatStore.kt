package com.littlebit.chat.data

import com.littlebit.chat.engine.ReplyTiming
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class ChatSession(val id: String, title: String, updatedAt: Long, val messages: MutableList<ChatMessage>, pinned: Boolean = false) {
    var title by mutableStateOf(title)
    var updatedAt by mutableStateOf(updatedAt)
    var pinned by mutableStateOf(pinned)

    /** Messages dropped from the start of this chat to stay inside [ChatBudget]. */
    var trimmed: Int = 0
}

/**
 * JSON chat history with guarded writes and recoverable streaming drafts.
 *
 * Writes stream one message at a time so a long chat never needs the whole
 * history encoded as a single `String`. Reads refuse a file above
 * [ChatBudget.MAX_FILE_BYTES] and keep only the newest
 * [ChatBudget.MAX_MESSAGES_PER_CHAT] messages per chat, both with a visible,
 * recoverable message instead of an OOM kill.
 */
class ChatStore(private val file: File) {
    val sessions = mutableStateListOf<ChatSession>()
    @Volatile var lastError: String? = null
        private set

    /** True when the history file was refused for size; the UI offers export. */
    @Volatile var oversized: Boolean = false
        private set

    @Synchronized
    fun load() {
        sessions.clear()
        oversized = false
        if (!file.exists()) return
        if (ChatBudget.isOversized(file.length())) {
            oversized = true
            lastError = ChatBudget.overLimitMessage(file.length())
            Log.e("LittleBitChatStore", lastError!!)
            return
        }
        try {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("chats") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val messages = mutableStateListOf<ChatMessage>()
                val rows = o.optJSONArray("messages") ?: JSONArray()
                for (j in 0 until rows.length()) {
                    val m = rows.getJSONObject(j)
                    if (m.getString("role") == "assistant" && m.getString("content").isBlank()) continue
                    messages.add(ChatMessage(m.getString("role"), m.getString("content"), m.optBoolean("fast"), m.optBoolean("cutOff"),
                        m.optJSONObject("timing")?.let { t -> ReplyTiming(t.getInt("promptTokens"), t.getDouble("promptMs"),
                            t.getInt("tokens"), t.getDouble("genMs"), t.getDouble("firstMs"), t.getInt("used"), t.getInt("capacity")) },
                        m.optString("backend"), m.optJSONArray("sources")?.let { links -> List(links.length()) { links.getString(it) } }.orEmpty(),
                        m.optString("searchQuery")))
                }
                val dropped = ChatBudget.trimmedCount(messages.size)
                val kept = if (dropped > 0) {
                    messages.drop(dropped).toMutableList<ChatMessage>().also {
                        it.add(0, ChatMessage("system", ChatBudget.trimmedNotice(dropped)))
                    }
                } else {
                    messages
                }
                sessions.add(ChatSession(
                    id = o.getString("id"),
                    title = o.optString("title", "New chat"),
                    updatedAt = o.optLong("updatedAt", 0L),
                    messages = kept, pinned = o.optBoolean("pinned"),
                ).also { it.trimmed = o.optInt("trimmed", dropped) })
            }
            sessions.sortByDescending { it.updatedAt }
            lastError = null
        } catch (t: Throwable) {
            lastError = "Could not read chat history: ${t.message ?: t.javaClass.simpleName}"
            Log.e("LittleBitChatStore", lastError!!, t)
        }
    }

    /**
     * Streams the history to `<file>.tmp` and renames it into place. Peak heap
     * is one encoded message plus the writer buffer, not the whole document.
     */
    @Synchronized
    fun save(): Boolean {
        if (oversized) {
            lastError = ChatBudget.overLimitMessage(file.length())
            return false
        }
        val parent = file.parentFile ?: file.absoluteFile.parentFile
        val tmp = File(parent, file.name + ".tmp")
        return try {
            if (!parent.exists() && !parent.mkdirs()) throw IllegalStateException("Could not create chat history directory.")
            tmp.bufferedWriter(Charsets.UTF_8).use { writer ->
                ChatJson.writeHeader(writer)
                sessions.forEachIndexed { index, session ->
                    ChatJson.writeSeparator(writer, index == 0)
                    ChatJson.writeSession(writer, session.id, session.title, session.updatedAt,
                        session.pinned, session.trimmed, session.messages)
                }
                ChatJson.writeFooter(writer)
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            lastError = null
            true
        } catch (t: Throwable) {
            tmp.delete()
            lastError = "Could not save chat history: ${t.message ?: t.javaClass.simpleName}"
            Log.e("LittleBitChatStore", lastError!!, t)
            false
        }
    }

    @Synchronized
    fun persistDraft(session: ChatSession, visible: List<ChatMessage>, partial: String) {
        session.messages.clear()
        session.messages.addAll(visible)
        if (partial.isNotEmpty()) session.messages.add(ChatMessage("assistant", partial, cutOff = true))
        session.updatedAt = System.currentTimeMillis()
        save()
    }

    @Synchronized
    fun create(): ChatSession {
        val session = ChatSession(UUID.randomUUID().toString(), "New chat", System.currentTimeMillis(), mutableStateListOf())
        sessions.add(0, session)
        save()
        return session
    }

    @Synchronized
    fun delete(id: String) {
        sessions.removeAll { it.id == id }
        save()
    }

    @Synchronized
    fun touch(session: ChatSession) {
        if (session.title == "New chat") {
            val first = session.messages.firstOrNull { it.role == "user" }?.content
            if (!first.isNullOrBlank()) session.title = first.trim().split(Regex("\\s+"), limit = 7).take(6).joinToString(" ").take(80)
        }
        session.updatedAt = System.currentTimeMillis()
        sessions.sortByDescending { it.updatedAt }
        save()
    }
}
