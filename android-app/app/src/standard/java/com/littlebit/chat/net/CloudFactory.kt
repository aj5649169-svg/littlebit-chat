package com.littlebit.chat.net

import android.content.Context

object CloudFactory {
    @Volatile private var cached: CloudClient? = null

    fun create(context: Context): CloudClient =
        cached ?: synchronized(this) {
            cached ?: OpenAiCloudClient(context.applicationContext).also { cached = it }
        }
}
