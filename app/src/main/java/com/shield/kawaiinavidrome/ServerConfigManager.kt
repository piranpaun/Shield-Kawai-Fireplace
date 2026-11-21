package com.shield.kawaiinavidrome

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class ServerCredentials(
    val serverUrl: String,
    val username: String,
    val password: String
) {
    val isValid: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

class ServerConfigManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "navidrome_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveCredentials(url: String, user: String, pass: String) {
        val cleanUrl = url.trim().removeSuffix("/")
        prefs.edit()
            .putString("server_url", cleanUrl)
            .putString("username", user.trim())
            .putString("password", pass)
            .apply()
    }

    fun getCredentials(): ServerCredentials {
        return ServerCredentials(
            serverUrl = prefs.getString("server_url", "") ?: "",
            username = prefs.getString("username", "") ?: "",
            password = prefs.getString("password", "") ?: ""
        )
    }

    fun saveSceneMode(mode: Int) {
        prefs.edit().putInt("scene_mode", mode).apply()
    }

    fun getSceneMode(): Int = prefs.getInt("scene_mode", SceneTheme.REALISTIC.id)

    fun saveThemeIndex(index: Int) {
        prefs.edit().putInt("theme_index", index).apply()
    }

    fun getThemeIndex(): Int = prefs.getInt("theme_index", 0)
}
