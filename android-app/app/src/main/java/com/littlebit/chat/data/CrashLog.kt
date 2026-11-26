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
        return header + trace.trimEnd('\n') + "\n"
    }

    /** v1.2.0 wrote literal `\n` sequences; show those old logs as real lines. */
    fun normalize(raw: String): String = raw.replace("\\n", "\n")
}
