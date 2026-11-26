package com.littlebit.chat.data

/**
 * Formats the uncaught-exception breadcrumb shown under Settings.
 *
 * The header must contain real newlines: an escaped `\n` written from a Kotlin
 * string shows up literally in the Settings card, which made the log unreadable.
 */
object CrashLog {
    fun format(threadName: String, timestampMillis: Long, trace: String): String {
        val header = "Thread: $threadName\nTime: $timestampMillis\n\n"
        return header + normalize(trace).trimEnd('\n') + "\n"
    }

    /**
     * v1.2.0 wrote escaped `\n` / `\r\n` / `\t` sequences next to real
     * newlines, which showed up literally in the Settings card. Normalize both
     * forms so old and new logs read the same, then drop trailing spaces and
     * long blank runs without touching genuine stack-trace indentation.
     */
    fun normalize(raw: String): String = raw
        .replace("\r\n", "\n")
        .replace("\\r\\n", "\n")
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace(Regex("[ \t]+\n"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
}
