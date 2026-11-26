package com.littlebit.chat.data

import java.util.Base64

/**
 * An assistant is an optional, user-visible persona. It stays a thin wrapper
 * around the existing system prompt, with optional per-assistant sampling and
 * thinking overrides so the chat, pre-warm and KV paths need no special cases.
 */
data class Assistant(
    val id: String,
    val name: String,
    val systemPrompt: String,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val preferThinkMode: Boolean? = null,
)

object Assistants {

    const val GENERAL_ID = "general"
    const val CUSTOM_ID = "custom"

    val defaults: List<Assistant> = listOf(
        Assistant(GENERAL_ID, "General", SettingsStore.DEFAULT_SYSTEM_PROMPT),
        Assistant("coder", "Coder", "You are a precise coding assistant. Write complete, correct code and explain trade-offs briefly."),
        Assistant("tutor", "Concise Tutor", "Teach step by step with short examples. Check understanding before moving on. Keep answers tight."),
        Assistant("creative", "Creative Writer", "Write vivid, original prose with a distinctive voice. Avoid clichés and filler."),
    )

    /** Migrates a v1.2.x custom persona into a first-class assistant, once. */
    fun migrateCustomPersona(persona: String, systemPrompt: String, customPrompt: String): Assistant? {
        if (persona != "Custom") return null
        val prompt = systemPrompt.trim().ifBlank { customPrompt.trim() }
        if (prompt.isBlank() || prompt == SettingsStore.DEFAULT_SYSTEM_PROMPT) return null
        return Assistant(CUSTOM_ID, "Custom", prompt)
    }

    /** Per-assistant overrides fall back to the global settings. */
    fun overrides(assistant: Assistant?, globalTemperature: Double, globalMaxTokens: Int): Pair<Double, Int> =
        (assistant?.temperature ?: globalTemperature) to (assistant?.maxTokens ?: globalMaxTokens)
}

/**
 * Line-oriented, Base64 field encoding. Pure JVM (no `org.json`), so the
 * migration path is unit-testable without an Android runtime.
 */
object AssistantCodec {

    private val SEPARATOR = '\u001F'

    fun encode(list: List<Assistant>): String = list.joinToString("\n") { assistant ->
        encodeRow(
            listOf(
                assistant.id,
                assistant.name,
                assistant.systemPrompt,
                assistant.temperature?.toString().orEmpty(),
                assistant.maxTokens?.toString().orEmpty(),
                assistant.preferThinkMode?.toString().orEmpty(),
            ),
        )
    }

    fun decode(raw: String): List<Assistant> = raw.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val fields = decodeRow(line)
            if (fields.size < 3 || fields[0].isBlank() || fields[1].isBlank()) return@mapNotNull null
            Assistant(
                id = fields[0],
                name = fields[1],
                systemPrompt = fields[2],
                temperature = fields.getOrNull(3)?.toDoubleOrNull()?.takeIf { it in 0.0..2.0 },
                maxTokens = fields.getOrNull(4)?.toIntOrNull()?.takeIf { it in 16..32_768 },
                preferThinkMode = fields.getOrNull(5)?.toBooleanStrictOrNull(),
            )
        }
        .toList()

    internal fun encodeRow(fields: List<String>): String = fields.joinToString(SEPARATOR.toString()) { value ->
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    }

    internal fun decodeRow(line: String): List<String> = line.split(SEPARATOR).map { field ->
        runCatching { String(Base64.getDecoder().decode(field), Charsets.UTF_8) }.getOrDefault("")
    }
}
