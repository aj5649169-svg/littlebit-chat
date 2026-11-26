package com.littlebit.chat.engine

/**
 * Turns a Markdown assistant reply into speech-safe plain text, and splits it
 * into sentence-safe chunks that fit the platform TTS queue.
 */
object TtsTextSanitizer {

    const val MAX_CHUNK = 3_000

    private val FENCED_CODE = Regex("(?s)```.*?```")
    private val REASONING = Regex("(?s)<think(?:ing)?>.*?</think(?:ing)?>")
    private val UNCLOSED_REASONING = Regex("(?s)<think(?:ing)?>.*")
    private val INLINE_CODE = Regex("`([^`]*)`")
    private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val HEADING = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
    private val BULLET = Regex("(?m)^\\s{0,3}(?:[-*+]|\\d+[.)])\\s+")
    private val QUOTE = Regex("(?m)^\\s{0,3}>\\s?")
    private val MARKUP = Regex("[*_~]{1,3}")
    private val HTML_TAG = Regex("<[^>]{1,120}>")
    private val SENTENCE_END = Regex("(?<=[.!?])\\s+")

    fun sanitize(raw: String): String {
        var text = REASONING.replace(raw, " ")
        text = UNCLOSED_REASONING.replace(text, " ")
        text = FENCED_CODE.replace(text, " ")
        text = INLINE_CODE.replace(text, "$1")
        text = IMAGE.replace(text, "$1")
        text = LINK.replace(text, "$1")
        text = HEADING.replace(text, "")
        text = BULLET.replace(text, "")
        text = QUOTE.replace(text, "")
        text = MARKUP.replace(text, "")
        text = HTML_TAG.replace(text, " ")
        text = text.replace('\u00A0', ' ')
        return text.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /** Sentence-safe chunks, each at most [max] characters. Blank input yields no chunks. */
    fun chunks(text: String, max: Int = MAX_CHUNK): List<String> {
        val limit = max.coerceAtLeast(200)
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()

        val sentences = ArrayList<String>()
        var start = 0
        for (match in SENTENCE_END.findAll(clean)) {
            sentences.add(clean.substring(start, match.range.first))
            start = match.range.last + 1
        }
        if (start < clean.length) sentences.add(clean.substring(start))

        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) {
                out.add(current.toString().trim())
                current.setLength(0)
            }
        }
        for (sentence in sentences) {
            val part = sentence.trim()
            if (part.isEmpty()) continue
            if (part.length > limit) {
                flush()
                var i = 0
                while (i < part.length) {
                    var end = minOf(i + limit, part.length)
                    if (end < part.length) {
                        val space = part.lastIndexOf(' ', end)
                        if (space > i + limit / 2) end = space
                    }
                    val slice = part.substring(i, end).trim()
                    if (slice.isNotEmpty()) out.add(slice)
                    i = end
                    while (i < part.length && part[i] == ' ') i++
                }
                continue
            }
            if (current.length + part.length + 1 > limit) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(part)
        }
        flush()
        return out.filter { it.length <= limit }
    }
}
