package com.marnie.pos.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central place for everything security-sensitive on-device:
 *  - A hardware-backed MasterKey (Android Keystore, AES256-GCM) protects an
 *    EncryptedSharedPreferences file used for auth tokens and the local
 *    SQLCipher database passphrase.
 *  - The SQLCipher passphrase itself is a random 256-bit value generated
 *    once per install and never leaves the device — this means the local
 *    Room database (which mirrors customer debt/PII) is encrypted at rest
 *    and unreadable if the device storage is extracted (e.g. rooted device,
 *    lost/stolen phone, ADB backup of a rooted device).
 */
@Singleton
class CryptoManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "marnie_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun saveAccessToken(token: String) = encryptedPrefs.edit().putString(KEY_ACCESS_TOKEN, token).apply()
    fun getAccessToken(): String? = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)

    fun saveRefreshToken(token: String) = encryptedPrefs.edit().putString(KEY_REFRESH_TOKEN, token).apply()
    fun getRefreshToken(): String? = encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)

    fun saveSession(userId: String, displayName: String?) {
        encryptedPrefs.edit()
            .putString(KEY_USER_ID, userId)
            .putString(KEY_DISPLAY_NAME, displayName)
            .apply()
    }

    fun getUserId(): String? = encryptedPrefs.getString(KEY_USER_ID, null)
    fun getDisplayName(): String? = encryptedPrefs.getString(KEY_DISPLAY_NAME, null)

    fun clearSession() = encryptedPrefs.edit().clear().apply()

    /** Returns the persistent per-install SQLCipher passphrase, generating one on first run. */
    fun getOrCreateDbPassphrase(): ByteArray {
        val existing = encryptedPrefs.getString(KEY_DB_PASSPHRASE, null)
        if (existing != null) return existing.hexToBytes()
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        encryptedPrefs.edit().putString(KEY_DB_PASSPHRASE, bytes.toHex()).apply()
        return bytes
    }

    /** Stable per-install device identifier used to scope refresh tokens server-side. */
    fun getOrCreateDeviceId(): String {
        val existing = encryptedPrefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing
        val id = java.util.UUID.randomUUID().toString()
        encryptedPrefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_DB_PASSPHRASE = "db_passphrase"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
