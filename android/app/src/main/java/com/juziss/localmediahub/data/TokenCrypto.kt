package com.juziss.localmediahub.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encryption backed by an AndroidKeyStore key, used to protect the
 * bearer token at rest. Previously the token was persisted as plaintext in
 * the DataStore preferences file — readable on rooted devices or via
 * `run-as` on debuggable builds. The key never leaves the Keystore (no
 * hardware-backed requirement — software Keystore still protects the key
 * material from the app's own storage).
 *
 * Storage format: Base64(IV(12B) || ciphertext). Legacy plaintext values
 * fail Base64/GCM decoding and are returned verbatim by [decrypt], so
 * pre-existing installations keep working and are transparently upgraded to
 * ciphertext on the next save.
 */
object TokenCrypto {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "lmh_auth_token_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE_BYTES = 12

    /** Encrypts [plain]; returns null when the platform Keystore is unusable
     *  (callers may fall back to plaintext storage in that case). */
    fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }

    /** Decrypts an [encrypt]-produced value. Returns null when the value is
     *  not valid ciphertext (e.g. legacy plaintext tokens). */
    fun decrypt(encoded: String): String? {
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (bytes.size <= IV_SIZE_BYTES) return null
            val iv = bytes.copyOfRange(0, IV_SIZE_BYTES)
            val ciphertext = bytes.copyOfRange(IV_SIZE_BYTES, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
