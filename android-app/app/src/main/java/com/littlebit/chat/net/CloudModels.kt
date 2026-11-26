package com.littlebit.chat.net

/** A single OpenAI-compatible chat completion request. */
data class CloudRequest(
    val endpoint: String,
    val model: String,
    val messages: List<Pair<String, String>>,
    val temperature: Double,
    val maxTokens: Int,
    val title: String = "LittleBit Chat",
)

/** One streamed delta. `error` is set for provider error events. */
data class CloudChunk(
    val content: String = "",
    val reasoning: String = "",
    val error: String? = null,
)

/**
 * Transport contract. `src/offline` returns a no-op implementation with
 * `supported = false`; `src/standard` implements it with HttpURLConnection.
 * Nothing in this file touches the network.
 */
interface CloudClient {
    val supported: Boolean
    fun hasApiKey(): Boolean
    fun saveApiKey(key: String)
    fun clearApiKey()
    suspend fun stream(request: CloudRequest, onDelta: (CloudChunk) -> Unit)
    suspend fun listModels(endpoint: String): List<String>
}

enum class CloudPreset(val label: String, val baseUrl: String, val needsKey: Boolean) {
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", true),
    NVIDIA("NVIDIA NIM", "https://integrate.api.nvidia.com/v1", true),
    GROQ("Groq", "https://api.groq.com/openai/v1", true),
    OLLAMA("Ollama (Local LAN)", "http://127.0.0.1:11434/v1", false),
    CUSTOM("Custom OpenAI-compatible", "", false);

    companion object {
        fun byLabel(label: String): CloudPreset =
            entries.firstOrNull { it.label.equals(label, true) || it.name.equals(label, true) } ?: OPENROUTER
    }
}

/** Extracts streamed deltas from one `data:` JSON payload. */
object OpenAiDelta {
    fun parse(data: String): CloudChunk {
        if (data.contains("\"error\"")) {
            val message = JsonScan.stringField(data, "message")
                ?: JsonScan.stringField(data, "error")
            if (!message.isNullOrBlank()) return CloudChunk(error = message)
        }
        return CloudChunk(
            content = JsonScan.stringField(data, "content").orEmpty(),
            reasoning = JsonScan.stringField(data, "reasoning_content")
                ?: JsonScan.stringField(data, "reasoning")
                ?: "",
        )
    }
}
