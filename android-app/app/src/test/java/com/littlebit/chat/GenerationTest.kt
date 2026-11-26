package com.littlebit.chat

import android.content.Context
import android.content.SharedPreferences
import com.littlebit.chat.data.SettingsStore
import com.littlebit.chat.engine.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class GenerationTest {
    @Test fun captions() {
        val timing = ReplyTiming(152, 1400.0, 1168, 84000.0, 1420.0, 1320, 2048)
        assertEquals("4.2s", duration(4200.0))
        assertEquals("1m 0s", duration(60000.0))
        assertEquals("", timing.caption("Off"))
        // TTFT rides beside the rate in the finalized metrics.
        assertEquals("13.9 t/s (1m 24s) · 1420 ms TTFT", timing.caption("Minimal"))
        assertEquals("Prompt: 108.6 t/s (1.4s) · Gen: 13.9 t/s (1m 24s) · 1420 ms TTFT", timing.caption("Detailed"))
    }

    @Test fun outputLimitSurvivesSettingsRecreation() {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(context.getSharedPreferences("littlebit_settings", 0)).thenReturn(prefs)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(prefs.getString(anyString(), anyString())).thenAnswer { it.arguments[1] }
        `when`(prefs.getInt(anyString(), anyInt())).thenAnswer { it.arguments[1] }
        val store = SettingsStore(context)
        assertTrue(4096 in outputLimits)
        store.maxTokens = 4096
        verify(editor).putInt("max_tokens", 4096)
        verify(editor).apply()
        `when`(prefs.getInt("max_tokens", 1024)).thenReturn(4096)
        assertEquals(4096, SettingsStore(context).maxTokens)
        assertEquals(4096, outputBudget(4096, 8192, 2048))
        assertEquals(47, outputBudget(4096, 2048, 2000))
        assertEquals(0, outputBudget(4096, 2048, 2048))
    }

    @Test fun fastModeUsesModelArchitecture() {
        assertEquals("hi", ThinkBlocks.withNoThink("hi", "gemma4"))
        assertEquals("hi", ThinkBlocks.withNoThink("hi", "gemma3"))
        assertEquals("hi", ThinkBlocks.withNoThink("hi", null))
        assertEquals("hi /no_think", ThinkBlocks.withNoThink("hi", "qwen3"))
    }
}
