package com.littlebit.chat.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.coroutineContext

/**
 * Minimal OpenAI-compatible streaming client built on HttpURLConnection.
 *
 * - `POST {base}/chat/completions`, `Accept: text/event-stream`
 * - skips blank lines and `: OPENROUTER PROCESSING` keep-alives
 * - accepts `data:` and `data: `, stops on `[DONE]`
 * - reads `errorStream` for non-2xx responses so the provider message survives
 * - disconnects from the cancellation handler, so Stop aborts a blocking read
 */
class OpenAiCloudClient(context: Context) : CloudClient {

    private val keys = ApiKeyStore(context)

    override val supported: Boolean = true

    override fun hasApiKey(): Boolean = keys.hasKey()

    override fun saveApiKey(key: String) = keys.save(key)

    override fun clearApiKey() = keys.clear()

    override suspend fun stream(request: CloudRequest, onDelta: (CloudChunk) -> Unit) = withContext(Dispatchers.IO) {
        val url = CloudEndpointValidator.chatCompletions(request.endpoint)
            ?: throw IOException(INVALID_ENDPOINT)
        require(request.model.isNotBlank()) { "Enter a model ID for this provider." }
        val apiKey = keys.read()
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        val cancellation = coroutineContext[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            runCatching { connection.disconnect() }
        }
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.setRequestProperty("User-Agent", "LittleBitChat/1.3")
            if (!apiKey.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
            if (url.contains("openrouter.ai")) {
                connection.setRequestProperty("HTTP-Referer", REFERER)
                connection.setRequestProperty("X-Title", request.title)
            }
            val payload = requestBody(request).toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }

            val code = connection.responseCode
            if (code !in 200..299) {
                val body = readError(connection)
                val detail = JsonScan.stringField(body, "message") ?: body.take(400).ifBlank { "no response body" }
                throw IOException(
                    if (code == 401 || code == 403) "HTTP $code — check the API key in Settings → Cloud API. $detail"
                    else "HTTP $code: $detail",
                )
            }

            val reader = connection.inputStream.bufferedReader()
            try {
                val buffer = SseParser.LineBuffer()
                val chars = CharArray(8192)
                var done = false
                while (!done) {
                    val count = reader.read(chars)
                    if (count < 0) {
                        for (event in buffer.flush()) done = deliver(event, onDelta) || done
                        break
                    }
                    for (event in buffer.feed(String(chars, 0, count))) {
                        if (deliver(event, onDelta)) {
                            done = true
                            break
                        }
                    }
                }
            } finally {
                runCatching { reader.close() }
            }
        } finally {
            cancellation?.dispose()
            runCatching { connection.disconnect() }
        }
    }

    override suspend fun listModels(endpoint: String): List<String> = withContext(Dispatchers.IO) {
        val url = CloudEndpointValidator.models(endpoint) ?: throw IOException(INVALID_ENDPOINT)
        val apiKey = keys.read()
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "LittleBitChat/1.3")
            if (!apiKey.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = readError(connection)
                val detail = JsonScan.stringField(body, "message") ?: body.take(400)
                throw IOException("HTTP $code: ${detail.ifBlank { "no response body" }}")
            }
            val body = connection.inputStream.bufferedReader().use { reader ->
                val text = StringBuilder()
                val chars = CharArray(4096)
                while (true) {
                    val n = reader.read(chars)
                    if (n < 0) break
                    require(text.length + n <= 1_000_000) { "Model list is too large." }
                    text.append(chars, 0, n)
                }
                text.toString()
            }
            JsonScan.allStringValues(body, "id").distinct().sorted()
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** @return true when the stream signalled `[DONE]`. */
    private fun deliver(event: String, onDelta: (CloudChunk) -> Unit): Boolean {
        if (SseParser.isDone(event)) return true
        val delta = OpenAiDelta.parse(event)
        delta.error?.let { throw IOException(it) }
        if (delta.content.isNotEmpty() || delta.reasoning.isNotEmpty()) onDelta(delta)
        return false
    }

    private fun readError(connection: HttpURLConnection): String =
        runCatching { connection.errorStream?.bufferedReader()?.use { it.readText().take(8000) } }.getOrNull().orEmpty()

    private fun requestBody(request: CloudRequest): String = buildString {
        append('{')
        append("\"model\":").append(JsonScan.quote(request.model))
        append(",\"messages\":[")
        request.messages.forEachIndexed { index, (role, content) ->
            if (index > 0) append(',')
            append("{\"role\":").append(JsonScan.quote(role))
            append(",\"content\":").append(JsonScan.quote(content)).append('}')
        }
        append("],\"stream\":true")
        append(",\"temperature\":").append(request.temperature)
        append(",\"max_tokens\":").append(request.maxTokens)
        append('}')
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 180_000
        const val REFERER = "https://github.com/5649169-svg/littlebit-chat"
        const val INVALID_ENDPOINT =
            "Enter a valid endpoint. Public hosts must use https://; http:// is allowed only for localhost and private LAN addresses."
    }
}
