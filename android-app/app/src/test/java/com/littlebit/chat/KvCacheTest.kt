package com.littlebit.chat

import com.littlebit.chat.engine.KvCacheMeta
import com.littlebit.chat.engine.KvCacheStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class KvCacheTest {

    private fun meta(chatId: String, savedAt: Long, fingerprint: String = "fp") = KvCacheMeta(
        chatId = chatId,
        modelFingerprint = fingerprint,
        nCtx = 4096,
        kvCacheType = "Q8_0",
        backendMode = "GPU · Vulkan",
        nGpuLayers = 99,
        llamaCommit = KvCacheStore.LLAMA_COMMIT,
        savedTokenCount = 1200,
        promptHash = "abc123",
        savedAt = savedAt,
    )

    @Test fun metadataRoundTripsThroughJson() {
        val original = meta("chat", 1L, fingerprint = "model \"quoted\"|8B")
        val parsed = KvCacheMeta.fromJson(original.toJson())
        assertEquals(original, parsed)
    }

    @Test fun restoreRequiresEverySidecarField() {
        val m = meta("chat", 1L)
        assertTrue(m.matches("fp", 4096, "Q8_0", "GPU · Vulkan", 99, 1200, "abc123"))
        assertTrue(m.matches("fp", 4096, "Q8_0", "GPU · Vulkan", 99, null, "abc123"))
        assertFalse(m.matches("other", 4096, "Q8_0", "GPU · Vulkan", 99, 1200, "abc123"))
        assertFalse(m.matches("fp", 2048, "Q8_0", "GPU · Vulkan", 99, 1200, "abc123"))
        assertFalse(m.matches("fp", 4096, "F16", "GPU · Vulkan", 99, 1200, "abc123"))
        assertFalse(m.matches("fp", 4096, "Q8_0", "CPU · KleidiAI", 99, 1200, "abc123"))
        assertFalse(m.matches("fp", 4096, "Q8_0", "GPU · Vulkan", 0, 1200, "abc123"))
        assertFalse(m.matches("fp", 4096, "Q8_0", "GPU · Vulkan", 99, 1199, "abc123"))
        assertFalse(m.matches("fp", 4096, "Q8_0", "GPU · Vulkan", 99, 1200, "other-hash"))
        assertFalse(m.copy(llamaCommit = "0000000").matches("fp", 4096, "Q8_0", "GPU · Vulkan", 99, 1200, "abc123"))
    }

    @Test fun conversationTagIsOrderAndRoleSensitive() {
        val base = listOf("user" to "hello", "assistant" to "hi")
        assertEquals(KvCacheStore.conversationTag(base), KvCacheStore.conversationTag(base.toList()))
        assertNotEquals(KvCacheStore.conversationTag(base), KvCacheStore.conversationTag(base.reversed()))
        assertNotEquals(KvCacheStore.conversationTag(base), KvCacheStore.conversationTag(listOf("assistant" to "hello", "user" to "hi")))
        assertEquals(64, KvCacheStore.sha256("x").length)
    }

    @Test fun commitIsAtomicAndRequiresAStateFile() {
        val dir = Files.createTempDirectory("kv-commit").toFile()
        assertFalse(KvCacheStore.commit(dir, "chat", meta("chat", 1L)))
        File(dir, "chat.kv.tmp").writeText("state")
        assertTrue(KvCacheStore.commit(dir, "chat", meta("chat", 1L)))
        assertTrue(File(dir, "chat.kv").isFile)
        assertFalse(File(dir, "chat.kv.tmp").exists())
        assertEquals("chat", KvCacheStore.readMeta(KvCacheStore.metaFile(dir, "chat"))?.chatId)
        assertTrue(KvCacheStore.sizeBytes(dir) > 0L)
    }

    @Test fun pruneKeepsTheJustSavedChatAndTheNewestOther() {
        val dir = Files.createTempDirectory("kv-lru").toFile()
        val now = System.currentTimeMillis()
        for ((id, at) in listOf("old" to now - 3000, "mid" to now - 2000, "new" to now - 1000)) {
            File(dir, "$id.kv").writeText("state")
            KvCacheStore.writeAtomic(KvCacheStore.metaFile(dir, id), meta(id, at).toJson())
        }
        KvCacheStore.prune(dir, keepChatId = "old", max = 2)
        assertTrue(File(dir, "old.kv").isFile)
        assertTrue(File(dir, "new.kv").isFile)
        assertFalse(File(dir, "mid.kv").exists())
        assertFalse(File(dir, "mid.meta.json").exists())
        val metas = dir.listFiles { file -> file.name.endsWith(".meta.json") }!!.size
        assertEquals(2, metas)
        KvCacheStore.discard(dir, "old")
        assertFalse(File(dir, "old.kv").exists())
    }

    @Test fun byteFormattingIsReadable() {
        assertEquals("512 B", KvCacheStore.formatBytes(512))
        assertEquals("2 KB", KvCacheStore.formatBytes(2_000))
        assertEquals("25.0 MB", KvCacheStore.formatBytes(25_000_000))
        assertEquals("1.50 GB", KvCacheStore.formatBytes(1_500_000_000))
    }
}
