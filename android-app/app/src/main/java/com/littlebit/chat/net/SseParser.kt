package com.littlebit.chat.net

/**
 * Server-Sent Events framing for OpenAI-compatible chat completions.
 *
 * Handles `data:{...}` and `data: {...}`, ignores comment keep-alives
 * (`: OPENROUTER PROCESSING`), other SSE fields (`event:`, `id:`, `retry:`)
 * and blank lines, and joins multi-line `data:` payloads with `\n`.
 */
object SseParser {

    const val DONE = "[DONE]"

    class LineBuffer {
        private val pending = StringBuilder()
        private val data = StringBuilder()
        private var sawData = false

        fun feed(chunk: String): List<String> {
            if (chunk.isEmpty()) return emptyList()
            pending.append(chunk)
            val events = ArrayList<String>()
            while (true) {
                val newline = pending.indexOf("\n")
                if (newline < 0) break
                var line = pending.substring(0, newline)
                pending.delete(0, newline + 1)
                if (line.endsWith("\r")) line = line.dropLast(1)
                handle(line, events)
            }
            return events
        }

        /** Dispatches an unterminated final event, if the stream ended abruptly. */
        fun flush(): List<String> {
            val events = ArrayList<String>()
            if (pending.isNotEmpty()) {
                val line = pending.toString()
                pending.setLength(0)
                handle(line, events)
            }
            dispatch(events)
            return events
        }

        private fun handle(line: String, out: MutableList<String>) {
            if (line.isEmpty()) {
                dispatch(out)
                return
            }
            if (line.startsWith(":")) return // comment / keep-alive
            if (!line.startsWith("data:")) return // event:, id:, retry:
            var payload = line.substring(5)
            if (payload.startsWith(" ")) payload = payload.substring(1)
            if (sawData) data.append('\n')
            data.append(payload)
            sawData = true
        }

        private fun dispatch(out: MutableList<String>) {
            if (!sawData) return
            out.add(data.toString())
            data.setLength(0)
            sawData = false
        }
    }

    fun isDone(event: String): Boolean = event.trim() == DONE

    /** Extracts the payload from a single raw `data:` line, or null. */
    fun dataOf(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return null
        return trimmed.substring(5).trimStart()
    }
}
