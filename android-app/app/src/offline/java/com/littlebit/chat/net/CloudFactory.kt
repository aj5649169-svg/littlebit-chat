package com.littlebit.chat.net

import android.content.Context

/**
 * Offline edition: cloud APIs do not exist. This file contains no networking
 * imports, and `CloudClient.supported` keeps every cloud control hidden.
 */
object CloudFactory {
    fun create(context: Context): CloudClient = OfflineCloudClient
}

private object OfflineCloudClient : CloudClient {
    override val supported: Boolean = false
    override fun hasApiKey(): Boolean = false
    override fun saveApiKey(key: String) = Unit
    override fun clearApiKey() = Unit
    override suspend fun stream(request: CloudRequest, onDelta: (CloudChunk) -> Unit) =
        error("Cloud APIs require the Standard edition.")
    override suspend fun listModels(endpoint: String): List<String> = emptyList()
}
