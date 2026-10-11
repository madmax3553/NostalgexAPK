package io.github.madmax3553.staticgroot

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * The saved Jellyfin session: server address, access token and user id. Stored in
 * EncryptedSharedPreferences, with the key held by the Android Keystore. The token is never
 * written in plain text and never goes in the repo (CLAUDE.md).
 */
class SessionStore(context: Context) {

    data class Saved(val serverUrl: String, val accessToken: String, val userId: String)

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        FILE,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** Last server address used. Kept even after sign-out, so the field can be pre-filled. */
    fun lastServerUrl(): String = prefs.getString(KEY_SERVER, "").orEmpty()

    fun load(): Saved? {
        val server = prefs.getString(KEY_SERVER, null)
        val token = prefs.getString(KEY_TOKEN, null)
        val user = prefs.getString(KEY_USER, null)
        if (server.isNullOrEmpty() || token.isNullOrEmpty() || user.isNullOrEmpty()) return null
        return Saved(server, token, user)
    }

    fun save(serverUrl: String, accessToken: String, userId: String) {
        prefs.edit()
            .putString(KEY_SERVER, serverUrl)
            .putString(KEY_TOKEN, accessToken)
            .putString(KEY_USER, userId)
            .apply()
    }

    /** Forget the token and user. Keeps the server address. */
    fun clearSession() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_USER).apply()
    }

    companion object {
        private const val FILE = "staticgroot_session"
        private const val KEY_SERVER = "server_url"
        private const val KEY_TOKEN = "access_token"
        private const val KEY_USER = "user_id"
    }
}
