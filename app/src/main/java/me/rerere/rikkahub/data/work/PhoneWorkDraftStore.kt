package me.rerere.rikkahub.data.work

import android.content.Context
import androidx.core.content.edit

class PhoneWorkDraftStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(sessionId: String?): String = preferences.getString(key(sessionId), "").orEmpty()

    fun save(sessionId: String?, text: String) {
        preferences.edit {
            if (text.isBlank()) remove(key(sessionId)) else putString(key(sessionId), text)
        }
    }

    fun clear(sessionId: String?) {
        preferences.edit { remove(key(sessionId)) }
    }

    private fun key(sessionId: String?): String = sessionId?.takeIf(String::isNotBlank) ?: NEW_SESSION_KEY

    private companion object {
        const val PREFERENCES = "phone_work_drafts"
        const val NEW_SESSION_KEY = "__new_session__"
    }
}
