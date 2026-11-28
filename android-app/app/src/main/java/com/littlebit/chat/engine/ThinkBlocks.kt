package com.littlebit.chat.engine

/**
 * Helpers for Qwen3 ` thinking` reasoning blocks and the Fast/No-Think mode.
 *
 * Real-device findings (Realme P3 Ultra, Qwen3-8B-Q4_K_M):
 *  - ` thinking` chains double wait time and cause thermal throttling.
 *  - llama.cpp's `--jinja` template can crash Turn 2 with std::out_of_range
 *    when prior assistant turns contain ` thinking` blocks.
 *
 * Defence in depth:
 *  1. [withNoThink] appends `/no_think` to the user prompt in Fast mode.
 *  2. [strip] removes reasoning blocks from stored assistant turns before
 *     they are fed back into the chat template (Kotlin side).
 *  3. llama_jni.cpp falls back to plain ChatML if the GGUF template throws.
 *
 * Both `<thinking>` and ` thinking` are accepted: cloud models (and some
 * llama.cpp templates) emit the long form, and the two notations must be
 * parsed identically even when the closing tag arrives in a later chunk. The
 * streaming caller always passes the accumulated text, so a tag split across
 * chunks is just a substring of the whole.
 */
object ThinkBlocks {

    // Built from char codes: a literal angle-bracket tag can be eaten by text tooling.
    val SHORT_OPEN = Char(60) + "think" + Char(62)
    val SHORT_CLOSE = Char(60) + "/think" + Char(62)
    val OPEN_TAGS = listOf("<thinking>", SHORT_OPEN)
    val CLOSE_TAGS = listOf("</thinking>", SHORT_CLOSE)

    private val CLOSED = Regex("(?s)<think(?:ing)?>.*?</think(?:ing)?>")
    private val OPEN_TO_END = Regex("(?s)<think(?:ing)?>.*")

    /**
     * Remove reasoning blocks from a stored assistant message before it is
     * formatted into the next turn's prompt.
     */
    fun strip(text: String): String {
        val normalized = normalizeChannels(text)
        return OPEN_TO_END.replace(CLOSED.replace(normalized, ""), "").trim()
    }

    /**
     * Gemma 4 / Qwen3 emit channel control tokens instead of literal tags:
     * `<|channel>thought\n…<channel|>`. Fold them into the exact same
     * [SHORT_OPEN]/[SHORT_CLOSE] strings the parser and `llama_jni.cpp` use, so
     * the tags can never drift into a malformed `"<thinkthought"`.
     */
    fun normalizeChannels(text: String): String {
        if (text.isEmpty()) return text
        return text
            .replace("<|channel>thought", SHORT_OPEN)
            .replace("<|channel>analysis", SHORT_OPEN)
            .replace("<|channel>", SHORT_OPEN)
            .replace("<channel|>", SHORT_CLOSE)
    }

    class Split(val think: String?, val answer: String)

    /**
     * Split a (possibly still-streaming) raw model output into the reasoning
     * part and the visible answer. An unterminated reasoning block (cancelled
     * or still streaming) keeps the rest as reasoning and the text before the
     * opening tag as the answer.
     */
    fun split(raw: String): Split {
        var openIndex = -1
        var openTag = ""
        var closeTag = ""
        for (i in OPEN_TAGS.indices) {
            val at = raw.indexOf(OPEN_TAGS[i])
            if (at >= 0 && (openIndex < 0 || at < openIndex)) {
                openIndex = at
                openTag = OPEN_TAGS[i]
                closeTag = CLOSE_TAGS[i]
            }
        }
        if (openIndex < 0) return Split(null, raw)
        val answer = raw.substring(0, openIndex)
        val contentStart = openIndex + openTag.length
        val close = raw.indexOf(closeTag, contentStart)
        return if (close >= 0) {
            val think = raw.substring(contentStart, close).trim()
            Split(think.ifBlank { null }, joinAroundTag(answer, raw.substring(close + closeTag.length)))
        } else {
            Split(raw.substring(contentStart).trim().ifBlank { null }, answer)
        }
    }

    /**
     * Text before an opening tag and after its closing tag is one answer, so a
     * single space is kept at the seam instead of doubling it up. Everything
     * inside each side is left byte-for-byte as the model produced it.
     */
    private fun joinAroundTag(before: String, after: String): String {
        if (before.isEmpty() || after.isEmpty()) return before + after
        val left = before.trimEnd()
        val right = after.trimStart()
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        return "$left $right"
    }

    /** Qwen3 Fast mode: suppress chain-of-thought by appending `/no_think`. */
    fun withNoThink(prompt: String, architecture: String?): String =
        if (architecture in setOf("qwen3", "qwen3moe")) "$prompt /no_think" else prompt

    /**
     * Sentence-completeness guard for the speculative pre-warm: only a draft
     * ending in sentence punctuation (or a short standalone greeting) may
     * trigger background generation — never a mid-clause fragment.
     */
    fun isSentenceComplete(draft: String): Boolean {
        val t = draft.trim()
        if (t.isEmpty()) return false
        if (t.last() in ".?!") return true
        return t.lowercase() in setOf("hi", "hello", "hey", "yo")
    }
}
