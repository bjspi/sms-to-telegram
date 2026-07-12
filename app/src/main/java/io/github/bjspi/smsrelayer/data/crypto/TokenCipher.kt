package io.github.bjspi.smsrelayer.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the Telegram bot token at rest with an AES-256/GCM key that lives in
 * the Android Keystore, so the key material never leaves secure hardware and the
 * token is unreadable in DataStore file dumps and device backups.
 *
 * Payload format: `v1:<base64 iv>:<base64 ciphertext>`.
 *
 * [decrypt] returns `null` on any failure (corrupted payload, wiped keystore
 * after a backup restore) — callers treat that as "no token configured" and the
 * user simply re-enters it.
 */
class TokenCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) {

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return buildString {
            append(VERSION_PREFIX)
            append(Base64.encodeToString(cipher.iv, BASE64_FLAGS))
            append(SEPARATOR)
            append(Base64.encodeToString(ciphertext, BASE64_FLAGS))
        }
    }

    fun decrypt(payload: String): String? {
        return try {
            if (!payload.startsWith(VERSION_PREFIX)) return null
            val parts = payload.removePrefix(VERSION_PREFIX).split(SEPARATOR)
            if (parts.size != 2) return null

            val iv = Base64.decode(parts[0], BASE64_FLAGS)
            val ciphertext = Base64.decode(parts[1], BASE64_FLAGS)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    // Synchronized: two first-time encrypts racing through check-then-generate
    // would create two keys, leaving the loser's ciphertext undecryptable.
    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "sms_relayer_token_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION_PREFIX = "v1:"
        const val SEPARATOR = ":"
        const val GCM_TAG_BITS = 128
        const val KEY_BITS = 256
        const val BASE64_FLAGS = Base64.NO_WRAP
    }
}
