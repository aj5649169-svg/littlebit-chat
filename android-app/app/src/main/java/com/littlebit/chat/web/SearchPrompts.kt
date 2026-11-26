package com.littlebit.chat.web

import com.littlebit.chat.data.SettingsStore

/**
 * The system prompt has two mutually exclusive grounding clauses: the offline
 * refusal and the search-grounded instruction. Keeping them in one place means
 * a turn can never tell the model both "you cannot search" and "these are live
 * search results".
 */
object SearchPrompts {

    const val SEARCH_GROUNDED =
        "Live web search results are attached for this turn. Answer directly using these results and cite their source URLs. " +
            "Do NOT claim you lack internet access or that you cannot search the web."

    private val clauses = listOf(SettingsStore.OFFLINE, SEARCH_GROUNDED)

    /** Removes either grounding clause so a persona can be reused in both modes. */
    fun basePrompt(prompt: String): String {
        var out = prompt
        for (clause in clauses) out = out.replace(clause, "")
        return out.replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trimEnd()
    }

    /** Effective system prompt for the current turn. */
    fun forMode(prompt: String, grounded: Boolean): String =
        basePrompt(prompt) + "\n" + if (grounded) SEARCH_GROUNDED else SettingsStore.OFFLINE

    /** Appends pinned context notes as an explicit, user-authored block. */
    fun withNotes(system: String, notes: List<String>): String =
        if (notes.isEmpty()) system else buildString {
            append(system.trimEnd())
            append("\n\nPinned context notes from the user. Treat them as stable context for this conversation:\n")
            for (note in notes) append("- ").append(note.trim()).append('\n')
        }.trimEnd()
}
