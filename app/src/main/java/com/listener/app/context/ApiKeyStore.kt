package com.listener.app.context

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.listener.app.BuildConfig

class ApiKeyStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(context, "remote_credentials", MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(), EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    fun save(key: String) { require(key.isNotBlank()); prefs.edit().putString("openrouter", key.trim()).apply() }
    fun read(): String? = prefs.getString("openrouter", null)
        ?: BuildConfig.OPENROUTER_API_KEY.trim().takeIf { it.isNotBlank() }
    fun clear() = prefs.edit().remove("openrouter").apply()
}
