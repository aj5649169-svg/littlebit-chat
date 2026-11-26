package com.littlebit.chat

import com.littlebit.chat.engine.ModelPath
import org.junit.Assert.*
import org.junit.Test

class SafPathTest {
    @Test fun descriptorPathsRoundTrip() {
        assertEquals("/proc/self/fd/42", ModelPath.fromDescriptor(42))
        assertEquals(42, ModelPath.parseDescriptor("/proc/self/fd/42"))
        assertEquals(0, ModelPath.parseDescriptor("/proc/self/fd/0"))
        assertTrue(ModelPath.isDescriptorPath("/proc/self/fd/7"))
    }

    @Test fun rejectsPathsThatAreNotDescriptorPaths() {
        assertNull(ModelPath.parseDescriptor("/storage/emulated/0/Download/model.gguf"))
        assertNull(ModelPath.parseDescriptor("content://com.android.providers.downloads.documents/document/42"))
        assertNull(ModelPath.parseDescriptor("/proc/self/fd/"))
        assertNull(ModelPath.parseDescriptor("/proc/self/fd/12x"))
        assertNull(ModelPath.parseDescriptor("/proc/self/fd/-3"))
        assertFalse(ModelPath.isDescriptorPath("/proc/self/fd"))
    }

    @Test fun invalidDescriptorIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ModelPath.fromDescriptor(-1) }
    }
}
