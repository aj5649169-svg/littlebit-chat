package com.littlebit.chat

import com.littlebit.chat.data.CrashLog
import org.junit.Assert.*
import org.junit.Test

class CrashLogTest {
    @Test fun crashHeaderUsesRealNewlines() {
        val text = CrashLog.format("main", 1700L, "java.lang.IllegalStateException: boom\n\tat x.y(Z.kt:1)")
        assertTrue(text.startsWith("Thread: main\nTime: 1700\n\n"))
        assertTrue(text.contains("\tat x.y(Z.kt:1)"))
        assertFalse(text.contains("\\n"))
        assertTrue(text.endsWith("\n"))
    }

    @Test fun legacyEscapedNewlinesAreNormalized() {
        val normalized = CrashLog.normalize("Thread: main\\nTime: 1\\n\\njava.lang.RuntimeException: boom")
        assertTrue(normalized.startsWith("Thread: main\nTime: 1\n\n"))
        assertFalse(normalized.contains("\\n"))
    }
}
