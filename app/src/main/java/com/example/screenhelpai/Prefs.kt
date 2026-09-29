package com.example.screenhelpai

import android.content.Context

object Prefs {
    private const val NAME = "screen_help"
    private const val KEY_API = "gemini_api_key"

    fun apiKey(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_API, "").orEmpty()

    fun saveApiKey(context: Context, key: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString(KEY_API, key).apply()
    }
}
