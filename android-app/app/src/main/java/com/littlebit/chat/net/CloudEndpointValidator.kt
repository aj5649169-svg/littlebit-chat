package com.littlebit.chat.net

import java.net.URI

/**
 * Endpoint policy for the standard edition.
 *
 * Public hosts must use TLS. Cleartext `http://` is accepted only for the
 * loopback interface and private LAN ranges, which is what an on-device or
 * same-network Ollama server needs. User info, fragments and query strings are
 * rejected so a pasted URL cannot smuggle a different destination.
 */
object CloudEndpointValidator {

    fun normalize(raw: String): String? {
        val text = raw.trim().trimEnd('/')
        if (text.isEmpty()) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host ?: return null
        if (uri.userInfo != null || uri.fragment != null || uri.query != null) return null
        val tls = scheme == "https"
        if (!tls && !(scheme == "http" && isPrivateHost(host))) return null
        if (host.isBlank()) return null
        return text
    }

    fun isAllowed(raw: String): Boolean = normalize(raw) != null

    fun isPrivateHost(host: String): Boolean {
        val value = host.lowercase().trim('[', ']')
        if (value == "localhost" || value == "127.0.0.1" || value == "::1" || value == "0:0:0:0:0:0:0:1") return true
        val parts = value.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168)
    }

    fun chatCompletions(base: String): String? =
        normalize(base)?.let { "${stripApiSuffix(it)}/chat/completions" }

    fun models(base: String): String? =
        normalize(base)?.let { "${stripApiSuffix(it)}/models" }

    /** Accepts a pasted full endpoint (`…/chat/completions` or `…/models`) as a base URL. */
    private fun stripApiSuffix(url: String): String =
        url.removeSuffix("/chat/completions").removeSuffix("/models")
}
