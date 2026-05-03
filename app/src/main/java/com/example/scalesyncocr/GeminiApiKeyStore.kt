package com.example.scalesyncocr

import android.content.Context

class GeminiApiKeyStore(context: Context) {

    private val preferences = context.getSharedPreferences("gemini_settings", Context.MODE_PRIVATE)

    fun getApiKey(): String {
        return preferences.getString(KEY_API_KEY, "")?.trim().orEmpty()
    }

    fun saveApiKey(apiKey: String) {
        preferences.edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    fun hasApiKey(): Boolean = getApiKey().isNotBlank()

    private companion object {
        const val KEY_API_KEY = "gemini_api_key"
    }
}