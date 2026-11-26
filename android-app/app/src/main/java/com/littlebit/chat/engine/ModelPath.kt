package com.littlebit.chat.engine

/**
 * Paths handed to the native engines for Storage Access Framework documents.
 *
 * Android only exposes a picked document through a `ParcelFileDescriptor`, and
 * a provider may not allow the file to be re-opened by path. Both llama.cpp
 * (patched) and stable-diffusion.cpp therefore accept `/proc/self/fd/<N>` and
 * duplicate the descriptor instead of calling `fopen()` on the path. The
 * descriptor has to stay open for as long as the engine uses the model.
 */
object ModelPath {
    private const val FD_PREFIX = "/proc/self/fd/"

    /** `/proc/self/fd/<fd>` for a descriptor the caller already owns. */
    fun fromDescriptor(fd: Int): String {
        require(fd >= 0) { "Invalid file descriptor: $fd" }
        return FD_PREFIX + fd
    }

    /** The descriptor number encoded in a `/proc/self/fd/<N>` path, if any. */
    fun parseDescriptor(path: String): Int? {
        if (!path.startsWith(FD_PREFIX)) return null
        val suffix = path.substring(FD_PREFIX.length)
        if (suffix.isEmpty() || suffix.any { it !in '0'..'9' }) return null
        return suffix.toIntOrNull()?.takeIf { it >= 0 }
    }

    fun isDescriptorPath(path: String): Boolean = parseDescriptor(path) != null
}
