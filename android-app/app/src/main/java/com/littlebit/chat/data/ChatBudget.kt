package com.littlebit.chat.data

import java.util.Locale

/**
 * Hard bounds that keep chat history inside the process heap.
 *
 * A long chat used to be parsed into a full `JSONObject` tree and then written
 * back as one `String`, so peak memory was roughly twice the file size. On a
 * phone that is the difference between a slow save and an OOM kill that leaves
 * the history unopenable. These limits are deliberately generous for real use
 * and small enough that the guard actually fires before the LMK does.
 */
object ChatBudget {

    /** Refuse to parse a history file above this size. */
    const val MAX_FILE_BYTES: Long = 16L * 1024 * 1024

    /** Newest messages kept per chat when a file is larger than expected. */
    const val MAX_MESSAGES_PER_CHAT: Int = 300

    const val OVER_LIMIT = "History too large — export first"

    fun isOversized(sizeBytes: Long): Boolean = sizeBytes > MAX_FILE_BYTES

    /** Messages kept from the end of a chat when trimming. */
    fun retainedCount(total: Int): Int =
        if (total <= 0) 0 else if (total <= MAX_MESSAGES_PER_CHAT) total else MAX_MESSAGES_PER_CHAT

    /** Messages dropped from the start of a chat when trimming. */
    fun trimmedCount(total: Int): Int = (total - retainedCount(total)).coerceAtLeast(0)

    fun overLimitMessage(sizeBytes: Long): String = String.format(
        Locale.US,
        "Chat history is %.1f MB, above the %d MB in-memory limit. %s",
        sizeBytes / (1024.0 * 1024.0),
        MAX_FILE_BYTES / (1024 * 1024),
        OVER_LIMIT,
    )

    fun trimmedNotice(count: Int): String =
        "… $count earlier message${if (count == 1) "" else "s"} trimmed to keep history in memory. " +
            "Export before continuing very long chats."
}
