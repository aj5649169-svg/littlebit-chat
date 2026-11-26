package com.littlebit.chat.data

import android.content.Context

/** SharedPreferences persistence for assistants and the active selection. */
class AssistantStore(context: Context) {

    private val prefs = context.getSharedPreferences("littlebit_assistants", Context.MODE_PRIVATE)

    fun load(persona: String, systemPrompt: String, customPrompt: String): Pair<List<Assistant>, String?> {
        val saved = AssistantCodec.decode(prefs.getString(KEY_LIST, "").orEmpty())
        val list = ArrayList<Assistant>(if (saved.isEmpty()) Assistants.defaults else saved)
        val custom = Assistants.migrateCustomPersona(persona, systemPrompt, customPrompt)
        if (custom != null && list.none { it.id == custom.id }) list.add(custom)
        val active = prefs.getString(KEY_ACTIVE, null)?.takeIf { id -> list.any { it.id == id } }
        return list to active
    }

    fun save(assistants: List<Assistant>) {
        prefs.edit().putString(KEY_LIST, AssistantCodec.encode(assistants)).apply()
    }

    fun setActive(id: String?) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    private companion object {
        const val KEY_LIST = "assistants"
        const val KEY_ACTIVE = "active_id"
    }
}
