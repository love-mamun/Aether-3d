package com.example.data.local

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TAG = "VaultCryptoManager"
private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val KEY_ALIAS = "aether_sqlcipher_master_key"
private const val PREFS_NAME = "aether_vault_sec"
private const val KEY_WRAPPED_PASSPHRASE = "wrapped_sqlcipher_passphrase_v1"
private const val KEY_IV = "wrapped_sqlcipher_iv_v1"

/**
 * Generates and hardware-wraps a 256-bit SQLCipher database passphrase using AndroidKeyStore AES-GCM.
 * Falls back cleanly in headless JVM/Robolectric test environments where Keystore isn't provisioned.
 */
class VaultCryptoManager(context: Context) {

    private val appContext = context.applicationContext
    private val secPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    var isHardwareKeystoreBacked: Boolean = false
        private set

    fun getOrCreateDatabasePassphrase(): ByteArray {
        val existingCipherB64 = secPrefs.getString(KEY_WRAPPED_PASSPHRASE, null)
        val existingIvB64 = secPrefs.getString(KEY_IV, null)

        if (!existingCipherB64.isNullOrBlank() && !existingIvB64.isNullOrBlank()) {
            val unwrapped = unwrapWithKeystore(
                cipherBytes = Base64.decode(existingCipherB64, Base64.NO_WRAP),
                ivBytes = Base64.decode(existingIvB64, Base64.NO_WRAP)
            )
            if (unwrapped != null && unwrapped.isNotEmpty()) {
                return unwrapped
            }
        }

        // Generate fresh 32-byte (256-bit) cryptographically strong passphrase
        val rawPassphrase = ByteArray(32)
        SecureRandom().nextBytes(rawPassphrase)
        // Encode to hex ASCII bytes so SQLCipher accepts clean UTF-8 key material
        val hexPassphrase = rawPassphrase.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.UTF_8)

        val wrapped = wrapWithKeystore(hexPassphrase)
        if (wrapped != null) {
            secPrefs.edit()
                .putString(KEY_WRAPPED_PASSPHRASE, Base64.encodeToString(wrapped.first, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(wrapped.second, Base64.NO_WRAP))
                .apply()
            return hexPassphrase
        }

        // Robolectric / JVM fallback (no AndroidKeyStore provider)
        val fallbackKey = "fallback_test_passphrase_b64"
        val saved = secPrefs.getString(fallbackKey, null)
        if (!saved.isNullOrBlank()) {
            return Base64.decode(saved, Base64.NO_WRAP)
        }
        secPrefs.edit()
            .putString(fallbackKey, Base64.encodeToString(hexPassphrase, Base64.NO_WRAP))
            .apply()
        return hexPassphrase
    }

    private fun getOrCreateMasterKey(): SecretKey? {
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
            return null
        }
        return runCatching {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) {
                isHardwareKeystoreBacked = true
                return@runCatching existing
            }
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey().also {
                isHardwareKeystoreBacked = true
            }
        }.onFailure { err ->
            Log.w(TAG, "AndroidKeyStore unavailable (${err.message}); using fallback key store")
            isHardwareKeystoreBacked = false
        }.getOrNull()
    }

    private fun wrapWithKeystore(plainBytes: ByteArray): Pair<ByteArray, ByteArray>? {
        val key = getOrCreateMasterKey() ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(plainBytes)
            encrypted to cipher.iv
        }.onFailure {
            Log.w(TAG, "Failed wrapping SQLCipher passphrase", it)
        }.getOrNull()
    }

    private fun unwrapWithKeystore(cipherBytes: ByteArray, ivBytes: ByteArray): ByteArray? {
        val key = getOrCreateMasterKey() ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ivBytes))
            cipher.doFinal(cipherBytes)
        }.onFailure {
            Log.w(TAG, "Failed unwrapping SQLCipher passphrase", it)
        }.getOrNull()
    }
}
