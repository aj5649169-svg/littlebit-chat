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

    @Test fun mixedLiteralAndRealNewlinesNormalizeCleanly() {
        val raw = "Thread: main\\nTime: 7\n\\njava.lang.RuntimeException: boom\\r\\n\\tat a.b(C.kt:2)"
        val normalized = CrashLog.normalize(raw)
        assertEquals("Thread: main\nTime: 7\n\njava.lang.RuntimeException: boom\n\tat a.b(C.kt:2)", normalized)
        assertFalse(normalized.contains("\\n"))
        assertFalse(normalized.contains("\\r"))
    }

    @Test fun normalizeKeepsRealIndentationAndCapsBlankRuns() {
        val raw = "Thread: main\nTime: 2\n\n\n\tat a.b(C.kt:2)   \n"
        val normalized = CrashLog.normalize(raw)
        assertEquals("Thread: main\nTime: 2\n\n\tat a.b(C.kt:2)\n", normalized)
        val formatted = CrashLog.format("main", 9L, "java.lang.IllegalStateException: x\\n\\tat y.Z(Z.kt:3)")
        assertEquals("Thread: main\nTime: 9\n\njava.lang.IllegalStateException: x\n\tat y.Z(Z.kt:3)\n", formatted)
    }
}
