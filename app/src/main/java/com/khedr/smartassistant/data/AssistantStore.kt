package com.khedr.smartassistant.data

import android.content.Context
import org.json.JSONArray

/** Lightweight local MVP storage. Replace with Room/DataStore for production. */
class AssistantStore(context: Context) {
    private val prefs = context.getSharedPreferences("assistant_local", Context.MODE_PRIVATE)
    fun enabled() = prefs.getBoolean("enabled", false)
    fun setEnabled(value: Boolean) = prefs.edit().putBoolean("enabled", value).apply()
    fun autoSend() = prefs.getBoolean("auto_send", false)
    fun setAutoSend(value: Boolean) = prefs.edit().putBoolean("auto_send", value).apply()
    fun themeId() = prefs.getInt("theme_id", 0)
    fun setThemeId(value: Int) = prefs.edit().putInt("theme_id", value).apply()

    /** Small rolling diagnostic log (newest first) shown inside the app. */
    @Synchronized
    fun log(entry: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val old = (prefs.getString("diag_log", "") ?: "").split("\n").filter { it.isNotBlank() }
        val line = "$time  ${entry.replace("\n", " ").take(160)}"
        prefs.edit().putString("diag_log", (listOf(line) + old).take(10).joinToString("\n")).apply()
    }
    fun logText(): String = prefs.getString("diag_log", "") ?: ""
    fun clearLog() = prefs.edit().remove("diag_log").apply()

    fun allowedContacts(): Set<String> = prefs.getStringSet("allowed", emptySet())?.toSet() ?: emptySet()
    fun setAllowedContacts(value: Set<String>) = prefs.edit().putStringSet("allowed", value).apply()
    fun addContact(name: String) = setAllowedContacts(allowedContacts() + name.trim())

    /** Removes the contact together with its saved profile and canned replies. */
    fun removeContact(name: String) {
        setAllowedContacts(allowedContacts() - name)
        prefs.edit().remove("profile_$name").remove("replies_$name").apply()
    }

    fun profile(name: String): String = prefs.getString("profile_$name", "") ?: ""
    fun setProfile(name: String, value: String) = prefs.edit().putString("profile_$name", value).apply()
    fun replies(name: String): List<String> {
        val raw = prefs.getString("replies_$name", "[]") ?: "[]"
        return try { val arr = JSONArray(raw); (0 until arr.length()).map { arr.getString(it) } } catch (_: Exception) { emptyList() }
    }
    fun setReplies(name: String, values: List<String>) {
        val arr = JSONArray(); values.filter { it.isNotBlank() }.forEach(arr::put)
        prefs.edit().putString("replies_$name", arr.toString()).apply()
    }
}
