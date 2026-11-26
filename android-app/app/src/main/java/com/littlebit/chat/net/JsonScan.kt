package com.littlebit.chat.net

/**
 * Tiny JSON reader/writer for the streaming client and the KV sidecar files.
 *
 * The Android `org.json` implementation is a stub in local JVM unit tests, so
 * every pure component here avoids it. Only the shapes this app produces and
 * consumes are supported: flat string/number/boolean fields and arrays of
 * strings. Real parsing of an untrusted payload is not attempted.
 */
object JsonScan {

    fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (c in value) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    fun stringField(json: String, key: String): String? {
        val marker = "\"$key\""
        var from = 0
        while (true) {
            val at = json.indexOf(marker, from)
            if (at < 0) return null
            var i = at + marker.length
            while (i < json.length && json[i].isWhitespace()) i++
            if (i < json.length && json[i] == ':') {
                i++
                while (i < json.length && json[i].isWhitespace()) i++
                if (i < json.length && json[i] == '"') return readString(json, i)
            }
            from = at + marker.length
        }
    }

    fun numberField(json: String, key: String): Double? {
        val marker = "\"$key\""
        var from = 0
        while (true) {
            val at = json.indexOf(marker, from)
            if (at < 0) return null
            var i = at + marker.length
            while (i < json.length && json[i].isWhitespace()) i++
            if (i < json.length && json[i] == ':') {
                i++
                while (i < json.length && json[i].isWhitespace()) i++
                val start = i
                while (i < json.length && (json[i].isDigit() || json[i] in "+-.eE")) i++
                if (i > start) json.substring(start, i).toDoubleOrNull()?.let { return it }
            }
            from = at + marker.length
        }
    }

    fun longField(json: String, key: String): Long? = numberField(json, key)?.toLong()
    fun intField(json: String, key: String): Int? = numberField(json, key)?.toInt()

    fun booleanField(json: String, key: String): Boolean? {
        val marker = "\"$key\""
        var from = 0
        while (true) {
            val at = json.indexOf(marker, from)
            if (at < 0) return null
            var i = at + marker.length
            while (i < json.length && json[i].isWhitespace()) i++
            if (i < json.length && json[i] == ':') {
                i++
                while (i < json.length && json[i].isWhitespace()) i++
                if (json.startsWith("true", i)) return true
                if (json.startsWith("false", i)) return false
            }
            from = at + marker.length
        }
    }

    /** Every value of `"key":"..."` in the document (used by `GET /models`). */
    fun allStringValues(json: String, key: String): List<String> {
        val out = ArrayList<String>()
        val marker = "\"$key\""
        var from = 0
        while (true) {
            val at = json.indexOf(marker, from)
            if (at < 0) break
            var i = at + marker.length
            while (i < json.length && json[i].isWhitespace()) i++
            if (i < json.length && json[i] == ':') {
                i++
                while (i < json.length && json[i].isWhitespace()) i++
                if (i < json.length && json[i] == '"') readString(json, i)?.let(out::add)
            }
            from = at + marker.length
        }
        return out
    }

    private fun readString(s: String, quoteIndex: Int): String? {
        val out = StringBuilder()
        var i = quoteIndex + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '"') return out.toString()
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            if (i + 1 >= s.length) return null
            when (val escaped = s[i + 1]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    if (i + 5 >= s.length) return null
                    val code = s.substring(i + 2, i + 6).toIntOrNull(16) ?: return null
                    out.append(code.toChar())
                    i += 4
                }
                else -> out.append(escaped)
            }
            i += 2
        }
        return null
    }
}
