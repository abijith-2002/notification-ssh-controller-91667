package org.example.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * SecureStorage is a small helper responsible for securely storing sensitive data
 * (such as SSH passwords) using AndroidX Security Crypto.
 *
 * It uses a MasterKey with AES256_GCM and EncryptedSharedPreferences to keep values encrypted at rest.
 *
 * Usage:
 *   val storage = SecureStorage.getInstance(context)
 *   storage.setPassword("mySecret")
 *   val pwd = storage.getPassword()
 *   storage.clear()
 */
class SecureStorage private constructor(context: Context) {

    private val prefs = run {
        // Create or retrieve the master key for encryption/decryption.
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        // Create encrypted SharedPreferences instance.
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // PUBLIC_INTERFACE
    /**
     * Returns the stored password or null if not set.
     *
     * Note: Never log the returned value.
     */
    fun getPassword(): String? {
        return prefs.getString(KEY_PASSWORD, null)
    }

    // PUBLIC_INTERFACE
    /**
     * Stores or updates the password securely.
     *
     * Note: Never log secrets or include them in crash reports.
     */
    fun setPassword(password: String) {
        prefs.edit().putString(KEY_PASSWORD, password).apply()
    }

    // PUBLIC_INTERFACE
    /**
     * Clears all secure entries managed by this storage (including the password).
     */
    fun clear() {
        prefs.edit().remove(KEY_PASSWORD).apply()
    }

    companion object {
        private const val PREFS_FILE_NAME = "secure_prefs"
        private const val KEY_PASSWORD = "ssh_password"

        @Volatile
        private var instance: SecureStorage? = null

        // PUBLIC_INTERFACE
        /**
         * Returns the singleton instance of SecureStorage scoped to the application context.
         */
        fun getInstance(context: Context): SecureStorage {
            return instance ?: synchronized(this) {
                instance ?: SecureStorage(context.applicationContext).also { instance = it }
            }
        }
    }
}
