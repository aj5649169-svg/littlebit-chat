package com.littlebit.chat

import com.littlebit.chat.data.Assistant
import com.littlebit.chat.data.AssistantCodec
import com.littlebit.chat.data.Assistants
import com.littlebit.chat.data.ContextNote
import com.littlebit.chat.data.NoteCodec
import com.littlebit.chat.data.Notes
import com.littlebit.chat.data.SettingsStore
import org.junit.Assert.*
import org.junit.Test

class AssistantTest {

    @Test fun defaultsShipAWorkingBaseline() {
        assertTrue(Assistants.defaults.size >= 3)
        assertEquals(Assistants.GENERAL_ID, Assistants.defaults.first().id)
        assertEquals(SettingsStore.DEFAULT_SYSTEM_PROMPT, Assistants.defaults.first().systemPrompt)
        assertTrue(Assistants.defaults.map { it.name }.contains("Coder"))
        assertTrue(Assistants.defaults.map { it.name }.contains("Concise Tutor"))
        assertEquals(Assistants.defaults.map { it.id }.distinct().size, Assistants.defaults.size)
    }

    @Test fun assistantCodecRoundTripsOverridesAndMultilinePrompts() {
        val list = listOf(
            Assistant("a", "A", "Prompt line 1\nPrompt line 2 with | pipe", 0.3, 512, true),
            Assistant("b", "B", "Plain", null, null, null),
            Assistant("c", "C é", "", 1.15, 2048, false),
        )
        assertEquals(list, AssistantCodec.decode(AssistantCodec.encode(list)))
    }

    @Test fun malformedRowsAreSkippedInsteadOfCrashing() {
        val encoded = AssistantCodec.encode(listOf(Assistant("a", "A", "ok")))
        assertEquals(1, AssistantCodec.decode(encoded).size)
        assertEquals(0, AssistantCodec.decode("not-base64!!").size)
        assertEquals(0, AssistantCodec.decode("").size)
        // Out-of-range overrides are dropped, not clamped into surprising values.
        val decoded = AssistantCodec.decode(AssistantCodec.encodeRow(listOf("a", "A", "p", "9.5", "999999")))
        assertEquals(1, decoded.size)
        assertNull(decoded.first().temperature)
        assertNull(decoded.first().maxTokens)
    }

    @Test fun customPersonaMigratesOnlyOnce() {
        val migrated = Assistants.migrateCustomPersona("Custom", "Be very terse", "Be very terse")
        assertNotNull(migrated)
        assertEquals("Be very terse", migrated!!.systemPrompt)
        assertEquals(Assistants.CUSTOM_ID, migrated.id)
        assertNull(Assistants.migrateCustomPersona("Concise", SettingsStore.DEFAULT_SYSTEM_PROMPT, ""))
        assertNull(Assistants.migrateCustomPersona("Custom", SettingsStore.DEFAULT_SYSTEM_PROMPT, ""))
        assertNull(Assistants.migrateCustomPersona("Coder", "ignored", ""))
    }

    @Test fun overridesFallBackToGlobalSettings() {
        assertEquals(0.7 to 1024, Assistants.overrides(null, 0.7, 1024))
        val assistant = Assistant("x", "X", "p", temperature = 0.2, maxTokens = 2048)
        assertEquals(0.2 to 2048, Assistants.overrides(assistant, 0.7, 1024))
    }

    @Test fun notesFilterByChatAndEnabledState() {
        val notes = listOf(
            ContextNote("g", "", "Global fact"),
            ContextNote("c", "Chat", "Chat fact", chatId = "chat-1"),
            ContextNote("off", "Hidden", "disabled", enabled = false),
            ContextNote("other", "Other", "other chat", chatId = "chat-2"),
        )
        assertEquals(listOf("Global fact", "Chat: Chat fact"), Notes.contextLines(notes, "chat-1"))
        assertEquals(listOf("Global fact"), Notes.contextLines(notes, null))
        assertTrue(notes.first().isGlobal)
        assertFalse(notes[1].isGlobal)
    }

    @Test fun noteCodecKeepsPipesNewlinesAndGlobalScope() {
        val notes = listOf(
            ContextNote("n1", "Title", "Line1\nLine2 | pipe"),
            ContextNote("n2", "Scoped", "text", enabled = false, chatId = "chat-9"),
        )
        assertEquals(notes, NoteCodec.decode(NoteCodec.encode(notes)))
        assertEquals(emptyList<ContextNote>(), NoteCodec.decode(""))
    }
}
