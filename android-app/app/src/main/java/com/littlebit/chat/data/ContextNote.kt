package com.littlebit.chat.data

import android.content.Context
import java.util.Base64

/** A small, user-authored context note. `chatId == null` means it applies to every chat. */
data class ContextNote(
    val id: String,
    val title: String,
    val text: String,
    val enabled: Boolean = true,
    val chatId: String? = null,
) {
    val isGlobal get() = chatId == null
}

object Notes {
    /** Enabled notes for this chat: globals first, then per-chat notes. */
    fun contextLines(notes: List<ContextNote>, chatId: String?): List<String> = notes
        .filter { note ->
            note.enabled && note.text.isNotBlank() && (note.chatId == null || note.chatId == chatId)
        }
        .sortedBy { if (it.isGlobal) 0 else 1 }
        .map { note -> if (note.title.isBlank()) note.text.trim() else "${note.title.trim()}: ${note.text.trim()}" }
}

object NoteCodec {

    private val SEPARATOR = '\u001F'

    fun encode(notes: List<ContextNote>): String = notes.joinToString("\n") { note ->
        listOf(note.id, note.title, note.text, note.enabled.toString(), note.chatId.orEmpty())
            .joinToString(SEPARATOR.toString()) { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
    }

    fun decode(raw: String): List<ContextNote> = raw.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val fields = line.split(SEPARATOR).map { field ->
                runCatching { String(Base64.getDecoder().decode(field), Charsets.UTF_8) }.getOrDefault("")
            }
            if (fields.size < 3 || fields[0].isBlank()) return@mapNotNull null
            ContextNote(
                id = fields[0],
                title = fields[1],
                text = fields[2],
                enabled = fields.getOrNull(3)?.toBooleanStrictOrNull() ?: true,
                chatId = fields.getOrNull(4)?.takeIf { it.isNotBlank() },
            )
        }
        .toList()
}

class NotesStore(context: Context) {
    private val prefs = context.getSharedPreferences("littlebit_notes", Context.MODE_PRIVATE)

    fun load(): List<ContextNote> = NoteCodec.decode(prefs.getString(KEY, "").orEmpty())

    fun save(notes: List<ContextNote>) {
        prefs.edit().putString(KEY, NoteCodec.encode(notes)).apply()
    }

    private companion object {
        const val KEY = "pinned_notes"
    }
}
