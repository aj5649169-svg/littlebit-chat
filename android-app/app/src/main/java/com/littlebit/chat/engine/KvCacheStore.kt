package com.littlebit.chat.engine

import com.littlebit.chat.net.JsonScan
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Sidecar metadata for `<chatId>.kv`. A cache is reusable only when every field
 * still describes the loaded engine and the conversation prefix is byte-for-byte
 * the one that produced the KV state. `promptHash` is the SHA-256 of the
 * conversation prefix (roles + contents) — the native side additionally matches
 * tokens and re-prefills from the longest common prefix, so a mismatch is never
 * unsafe, only a cache miss.
 */
data class KvCacheMeta(
    val chatId: String,
    val modelFingerprint: String,
    val nCtx: Int,
    val kvCacheType: String,
    val backendMode: String,
    val nGpuLayers: Int,
    val llamaCommit: String,
    val savedTokenCount: Int,
    val promptHash: String,
    val savedAt: Long,
) {
    fun matches(
        modelFingerprint: String,
        nCtx: Int,
        kvCacheType: String,
        backendMode: String,
        nGpuLayers: Int,
        savedTokenCount: Int? = null,
        promptHash: String,
    ): Boolean =
        this.modelFingerprint == modelFingerprint &&
            this.nCtx == nCtx &&
            this.kvCacheType == kvCacheType &&
            this.backendMode == backendMode &&
            this.nGpuLayers == nGpuLayers &&
            (savedTokenCount == null || this.savedTokenCount == savedTokenCount) &&
            this.promptHash == promptHash &&
            llamaCommit == KvCacheStore.LLAMA_COMMIT

    fun toJson(): String = buildString {
        append('{')
        append("\"chatId\":").append(JsonScan.quote(chatId)).append(',')
        append("\"modelFingerprint\":").append(JsonScan.quote(modelFingerprint)).append(',')
        append("\"nCtx\":").append(nCtx).append(',')
        append("\"kvCacheType\":").append(JsonScan.quote(kvCacheType)).append(',')
        append("\"backendMode\":").append(JsonScan.quote(backendMode)).append(',')
        append("\"nGpuLayers\":").append(nGpuLayers).append(',')
        append("\"llamaCommit\":").append(JsonScan.quote(llamaCommit)).append(',')
        append("\"savedTokenCount\":").append(savedTokenCount).append(',')
        append("\"promptHash\":").append(JsonScan.quote(promptHash)).append(',')
        append("\"savedAt\":").append(savedAt)
        append('}')
    }

    companion object {
        fun fromJson(text: String): KvCacheMeta? {
            val chatId = JsonScan.stringField(text, "chatId") ?: return null
            val fingerprint = JsonScan.stringField(text, "modelFingerprint") ?: return null
            val kvType = JsonScan.stringField(text, "kvCacheType") ?: return null
            val backend = JsonScan.stringField(text, "backendMode") ?: return null
            val commit = JsonScan.stringField(text, "llamaCommit") ?: return null
            val hash = JsonScan.stringField(text, "promptHash") ?: return null
            val ctx = JsonScan.intField(text, "nCtx") ?: return null
            val layers = JsonScan.intField(text, "nGpuLayers") ?: return null
            val tokens = JsonScan.intField(text, "savedTokenCount") ?: return null
            val savedAt = JsonScan.longField(text, "savedAt") ?: 0L
            return KvCacheMeta(chatId, fingerprint, ctx, kvType, backend, layers, commit, tokens, hash, savedAt)
        }
    }
}

object KvCacheStore {

    const val LLAMA_COMMIT = "6aee508"
    const val MAX_ENTRIES = 2

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun conversationTag(messages: List<Pair<String, String>>): String {
        val builder = StringBuilder()
        for ((role, content) in messages) {
            builder.append(role).append('\u0000').append(content).append('\n')
        }
        return sha256(builder.toString())
    }

    fun stateFile(dir: File, chatId: String) = File(dir, "$chatId.kv")
    fun tempFile(dir: File, chatId: String) = File(dir, "$chatId.kv.tmp")
    fun metaFile(dir: File, chatId: String) = File(dir, "$chatId.meta.json")

    fun readMeta(file: File): KvCacheMeta? =
        if (!file.isFile) null else runCatching { KvCacheMeta.fromJson(file.readText()) }.getOrNull()

    fun writeAtomic(file: File, text: String): Boolean {
        val parent = file.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val tmp = File(parent, file.name + ".tmp")
        return try {
            tmp.writeText(text)
            move(tmp, file)
            true
        } catch (t: Throwable) {
            tmp.delete()
            false
        }
    }

    /** Promotes `<chatId>.kv.tmp` (written by native) and its metadata atomically. */
    fun commit(dir: File, chatId: String, meta: KvCacheMeta): Boolean {
        val state = stateFile(dir, chatId)
        val tmp = tempFile(dir, chatId)
        if (!tmp.isFile || tmp.length() <= 0L) {
            tmp.delete()
            return false
        }
        if (!move(tmp, state)) {
            tmp.delete()
            return false
        }
        if (!writeAtomic(metaFile(dir, chatId), meta.toJson())) {
            state.delete()
            return false
        }
        return true
    }

    fun discard(dir: File, chatId: String) {
        stateFile(dir, chatId).delete()
        metaFile(dir, chatId).delete()
        tempFile(dir, chatId).delete()
    }

    /** Keeps [keepChatId] plus the most recently saved entries, up to [max]. */
    fun prune(dir: File, keepChatId: String, max: Int = MAX_ENTRIES): Int {
        val metas = dir.listFiles { file -> file.isFile && file.name.endsWith(".meta.json") }
            ?.mapNotNull { file -> readMeta(file)?.let { it to file } }
            ?: return 0
        val ordered = metas.sortedByDescending { it.first.savedAt }
        val keep = (listOf(keepChatId) + ordered.map { it.first.chatId }).distinct().take(max.coerceAtLeast(1))
        var removed = 0
        for ((meta, file) in ordered) {
            if (meta.chatId !in keep) {
                discard(dir, meta.chatId)
                file.delete()
                removed++
            }
        }
        dir.listFiles { file -> file.isFile && file.name.endsWith(".kv") }?.forEach { file ->
            val chatId = file.name.removeSuffix(".kv")
            if (chatId !in keep) {
                file.delete()
                removed++
            }
        }
        return removed
    }

    fun sizeBytes(dir: File): Long =
        dir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".kv") || it.name.endsWith(".meta.json") || it.name.endsWith(".tmp")) }
            ?.sumOf { it.length() }
            ?: 0L

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1e9)
        bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1e6)
        bytes >= 1_000L -> "%.0f KB".format(bytes / 1e3)
        else -> "$bytes B"
    }

    private fun move(source: File, target: File): Boolean = try {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        true
    } catch (_: Exception) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: Exception) {
            source.renameTo(target)
        }
    }
}
