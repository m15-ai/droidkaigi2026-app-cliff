package com.m15.cliff.prefs

import android.content.Context
import android.content.SharedPreferences
import com.m15.cliff.net.LlmClient

class CliffLocalPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun getSystemMessage(): String = sp.getString(KEY_SYSTEM_MESSAGE, DEFAULT_SYSTEM_MESSAGE) ?: DEFAULT_SYSTEM_MESSAGE
    fun setSystemMessage(message: String) = sp.edit().putString(KEY_SYSTEM_MESSAGE, message).apply()

    fun getSpeakerOn(): Boolean = sp.getBoolean(KEY_SPEAKER_ON, DEFAULT_SPEAKER_ON)
    fun setSpeakerOn(on: Boolean) = sp.edit().putBoolean(KEY_SPEAKER_ON, on).apply()

    companion object {
        private const val FILE_NAME = "cliff_local_prefs"

        private const val KEY_SYSTEM_MESSAGE = "system_message"
        private const val DEFAULT_SYSTEM_MESSAGE = LlmClient.DEFAULT_SYSTEM_MESSAGE

        private const val KEY_SPEAKER_ON = "speaker_on"
        private const val DEFAULT_SPEAKER_ON = true
    }
}
