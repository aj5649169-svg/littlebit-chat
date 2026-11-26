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
        "now you can", "try again", "search it", "check now", "and now", "do it",
        "go on", "continue", "what about it", "look it up", "search again",
    )

    private val REFERENTIAL = listOf(
        "it", "that", "this", "them", "those", "there", "again", "now",
        "latest", "current", "today", "news",
    )

    fun isFollowUp(text: String): Boolean {
        val clean = text.trim().lowercase().trim(' ', '.', ',', '!', '?', ':', ';')
        if (clean.isEmpty()) return false
        if (clean in FOLLOW_UP_PHRASES) return true
        if (FOLLOW_UP_PHRASES.any { clean == it || clean.startsWith("$it ") }) return true
        val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 4) return true
        return words.size <= 6 && REFERENTIAL.any { word -> words.any { it == word } }
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

    private fun isSubstantive(text: String): Boolean =
        text.split(Regex("\\s+")).count { it.isNotBlank() } > 3
}
