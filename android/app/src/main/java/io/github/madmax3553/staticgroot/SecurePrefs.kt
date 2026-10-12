package io.github.madmax3553.staticgroot

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Opens an EncryptedSharedPreferences file without letting a Keystore fault crash the app.
 *
 * Opening throws when the master key no longer matches the file or cannot be read at all, which
 * happens after a backup restore, some OS updates and on TV boxes with a flaky Keystore. Before
 * this, that crashed every launch with no way out short of clearing app data. Whatever the file
 * held (a session, the companion address) is then unreadable anyway, so the right outcome is an
 * empty store and a sign-in, not a crash loop.
 *
 * The files share one master key, so recovery is ordered to keep one store's repair from
 * breaking another's: first delete only this file and retry, which fixes a file the key no
 * longer matches. Only if the key itself is the problem is it deleted, which makes a fresh one;
 * any other file still encrypted under the old key then fails and takes the first step itself.
 */
object SecurePrefs {

    private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"

    fun open(context: Context, file: String): SharedPreferences {
        try {
            return create(context, file)
        } catch (e: Exception) {
            Log.w("StaticGroot", "Secure store '$file' unreadable (${e.javaClass.simpleName}), clearing it")
        }
        context.deleteSharedPreferences(file)
        try {
            return create(context, file)
        } catch (e: Exception) {
            Log.w("StaticGroot", "Keystore master key unusable (${e.javaClass.simpleName}), creating a new one")
        }
        context.deleteSharedPreferences(file)
        runCatching {
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(MASTER_KEY_ALIAS)
        }
        return create(context, file)
    }

    private fun create(context: Context, file: String): SharedPreferences = EncryptedSharedPreferences.create(
        file,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}
