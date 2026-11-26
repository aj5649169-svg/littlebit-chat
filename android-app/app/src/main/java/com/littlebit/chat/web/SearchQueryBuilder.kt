package com.littlebit.chat.web

/**
 * Pure query builder for per-message web search.
 *
 * Short or referential follow-ups ("now you can", "try again", "search it")
 * are meaningless to a search engine on their own, so they are combined with
 * the most recent substantive user message in the same chat.
 */
object SearchQueryBuilder {

    private val FOLLOW_UP_PHRASES = setOf(
        "now you can", "try again", "search it", "check now", "and now",
        "what about now", "what about that", "do it", "go on",
    )

    /**
     * Pronouns and cues that can only point at the previous turn. A short
     * message without one ("weather in Varanasi", "Bitcoin price today") is a
     * complete search on its own and must not inherit earlier context.
     */
    private val REFERENTIAL = setOf(
        "it", "that", "this", "they", "them", "there", "he", "she", "same",
    )

    fun isFollowUp(text: String): Boolean {
        val clean = text.trim().lowercase().trim(' ', '.', ',', '!', '?', ':', ';')
        if (clean.isEmpty()) return false
        if (FOLLOW_UP_PHRASES.any { clean == it || clean.startsWith("$it ") }) return true
        val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.size <= 4 && words.any { word -> word in REFERENTIAL }
    }

    /**
     * @param current the text the user is sending now
     * @param history user messages in the chat, oldest first (current excluded)
     */
    fun build(current: String, history: List<String>, maxChars: Int = 384): String {
        val text = current.trim()
        if (text.isEmpty()) return ""
        val previous = history.asReversed().firstOrNull { candidate ->
            val trimmed = candidate.trim()
            trimmed.isNotEmpty() && trimmed != text && isSubstantive(trimmed)
        }
        val combined = if (previous != null && isFollowUp(text)) "${previous.trim()} $text" else text
        return combined.replace(Regex("\\s+"), " ").trim().take(maxChars.coerceAtLeast(1)).trim()
    }

    /** Anything longer than a bare greeting can carry search context. */
    private fun isSubstantive(text: String): Boolean =
        text.split(Regex("\\s+")).count { it.isNotBlank() } > 1
}
