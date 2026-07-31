package com.cloud.assistant

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecurePrefs {
    private const val FILE_NAME = "cloud_secure_prefs"
    private const val KEY_GEMINI = "gemini_api_key"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getKey(context: Context): String = prefs(context).getString(KEY_GEMINI, "") ?: ""

    fun setKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_GEMINI, value).apply()
    }
}
