package com.villamediana.novelo.data

import android.content.SharedPreferences

/**
 * What she was writing and hasn't sent: kept for [KEEP_MS] after her last change (a stray touch
 * that closes the sheet, the app closed), back where it was when she writes again; gone once sent.
 */
class Draft(private val prefs: SharedPreferences, private val clock: () -> Long = System::currentTimeMillis) {
    /** The text kept, or "" when there is none (or it is older than [KEEP_MS]). */
    fun read(): String {
        val text = prefs.getString(TEXT, null).orEmpty()
        if (text.isBlank()) return ""
        if (clock() - prefs.getLong(AT, 0) > KEEP_MS) {
            clear()
            return ""
        }
        return text
    }

    fun write(text: String) {
        if (text.isBlank()) clear() else prefs.edit().putString(TEXT, text).putLong(AT, clock()).apply()
    }

    fun clear() {
        prefs.edit().remove(TEXT).remove(AT).apply()
    }

    companion object {
        const val KEEP_MS = 24 * 60 * 60_000L
        private const val TEXT = "text"
        private const val AT = "at"
    }
}
