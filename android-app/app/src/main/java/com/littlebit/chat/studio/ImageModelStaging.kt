package com.littlebit.chat.studio

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException

/**
 * `stable-diffusion.cpp` opens weights with `std::ifstream`/`fopen`, which is
 * blocked for `/proc/self/fd/<N>` on Android 11–16 FUSE providers. Image Studio
 * therefore stages SAF documents into app-private storage once and hands the
 * engine a real path.
 *
 * Staging reuses a file whose display name and byte size already match, copies
 * through `<name>.tmp`, and renames atomically so a cancelled or failed copy can
 * never leave a half-written model behind.
 */
object ImageModelStaging {

    const val DIR = "image_models"

    fun directory(context: Context): File =
        File(context.filesDir, DIR).apply { if (!isDirectory) mkdirs() }

    fun safeName(displayName: String): String {
        val cleaned = displayName
            .replace('/', '_')
            .replace('\\', '_')
            .replace(Regex("[\\p{Cntrl}]"), "_")
            .trim()
            .ifBlank { "model.gguf" }
        return cleaned.take(120)
    }

    fun stagedFile(dir: File, displayName: String): File = File(dir, safeName(displayName))

    fun findStaged(dir: File, displayName: String, bytes: Long): File? =
        stagedFile(dir, displayName).takeIf { bytes > 0 && it.isFile && it.length() == bytes }

    fun stage(
        context: Context,
        uri: Uri,
        displayName: String,
        bytes: Long,
        cancelled: () -> Boolean = { false },
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
    ): File {
        val dir = directory(context)
        val target = stagedFile(dir, displayName)
        findStaged(dir, displayName, bytes)?.let { return it }
        val tmp = File(dir, target.name + ".tmp")
        tmp.delete()
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("The selected model could not be opened.")
            input.use { source ->
                tmp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var copied = 0L
                    var lastReport = 0L
                    onProgress(0L, bytes)
                    while (true) {
                        if (cancelled()) throw CancellationException("Staging cancelled")
                        val read = source.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (copied - lastReport >= 8L * 1024 * 1024 || (bytes > 0 && copied >= bytes)) {
                            onProgress(copied, bytes)
                            lastReport = copied
                        }
                    }
                    output.flush()
                    onProgress(copied, bytes)
                }
            }
            move(tmp, target)
            return target
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    fun delete(file: File): Boolean = !file.exists() || file.delete()

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1e9)
        bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1e6)
        bytes >= 1_000L -> "%.0f KB".format(bytes / 1e3)
        else -> "$bytes B"
    }

    private fun move(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            if (!source.renameTo(target)) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
