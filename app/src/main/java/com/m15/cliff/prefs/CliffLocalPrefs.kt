package com.m15.cliff.prefs

import android.content.Context
import android.content.SharedPreferences
import com.m15.cliff.net.LlmClient

class CliffLocalPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun getSystemMessage(): String {
        val stored = sp.getString(KEY_SYSTEM_MESSAGE, null)
        // Auto-upgrade installs that saved a superseded default (e.g. the old prompt
        // where Cliff had no name) so they pick up the current default.
        if (stored == null || stored in LEGACY_SYSTEM_MESSAGES) {
            return DEFAULT_SYSTEM_MESSAGE
        }
        return stored
    }
    fun setSystemMessage(message: String) = sp.edit().putString(KEY_SYSTEM_MESSAGE, message).apply()

    fun getSpeakerOn(): Boolean = sp.getBoolean(KEY_SPEAKER_ON, DEFAULT_SPEAKER_ON)
    fun setSpeakerOn(on: Boolean) = sp.edit().putBoolean(KEY_SPEAKER_ON, on).apply()

    companion object {
        private const val FILE_NAME = "cliff_local_prefs"

        private const val KEY_SYSTEM_MESSAGE = "system_message"
        private const val DEFAULT_SYSTEM_MESSAGE = LlmClient.DEFAULT_SYSTEM_MESSAGE

        // Superseded default prompts. A stored value matching one of these is treated
        // as "unset" so the install transparently moves to the current default.
        private val LEGACY_SYSTEM_MESSAGES = setOf(
            "You are a helpful voice assistant. Your responses are spoken aloud via text-to-speech. " +
                "Keep responses short and conversational — 1 to 3 sentences max. " +
                "Never use bullet points, numbered lists, markdown, emojis, or special formatting. " +
                "Speak in plain, natural sentences like a real conversation. " +
                "If a topic needs more detail, offer to explain further rather than dumping everything at once."
        )

        private const val KEY_SPEAKER_ON = "speaker_on"
        private const val DEFAULT_SPEAKER_ON = true
    }
}
