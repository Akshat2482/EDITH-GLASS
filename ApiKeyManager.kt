package com.akshat.edithglasses

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the Groq API key encrypted, on this device only.
 *
 * The key is entered by the user at runtime (ApiKeyDialog in MainActivity)
 * and never touches source code, build.gradle.kts, local.properties, or the
 * built APK — so it can never accidentally end up in a git commit again.
 * Backed by Jetpack Security's EncryptedSharedPreferences (AES-256).
 */
class ApiKeyManager(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            "edith_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getGroqApiKey(): String? =
        prefs.getString(KEY_GROQ_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun hasGroqApiKey(): Boolean = !getGroqApiKey().isNullOrBlank()

    fun saveGroqApiKey(key: String) {
        prefs.edit().putString(KEY_GROQ_API_KEY, key.trim()).apply()
    }

    fun clearGroqApiKey() {
        prefs.edit().remove(KEY_GROQ_API_KEY).apply()
    }

    companion object {
        private const val KEY_GROQ_API_KEY = "groq_api_key"
    }
}
