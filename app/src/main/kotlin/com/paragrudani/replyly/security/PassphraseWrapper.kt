package com.paragrudani.replyly.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed interface UnwrapResult {
    class Ok(
        val plain: ByteArray,
    ) : UnwrapResult

    /** The wrapping key is gone or permanently invalidated: the passphrase cannot come back. */
    data object KeyLost : UnwrapResult

    /** The bytes are damaged, in an unknown format, or were wrapped with another key. */
    data object Corrupt : UnwrapResult
}

/**
 * Encrypts the passphrase for storage. Lost keys and bad bytes come back as [UnwrapResult]s; any
 * exception means a transient failure.
 */
interface PassphraseWrapper {
    /** Creates the wrapping key if there is none. */
    fun wrap(plain: ByteArray): ByteArray

    fun unwrap(wrapped: ByteArray): UnwrapResult

    fun deleteKey()
}

/** Where the AES key lives. The seam that lets tests use a software key instead of the Keystore. */
interface SecretKeySource {
    /** `null` if there is no usable key. */
    fun get(): SecretKey?

    fun create(): SecretKey

    fun delete()
}

/**
 * AES-256-GCM wrapping. Format: `[version=1][iv length][iv][ciphertext + 128-bit tag]`, with a
 * fixed associated-data label so the bytes cannot be reused for another purpose.
 */
class AesGcmPassphraseWrapper(
    private val keys: SecretKeySource,
) : PassphraseWrapper {
    override fun wrap(plain: ByteArray): ByteArray {
        val key = keys.get() ?: keys.create()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(AAD)
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain)
        return byteArrayOf(VERSION, iv.size.toByte()) + iv + sealed
    }

    override fun unwrap(wrapped: ByteArray): UnwrapResult {
        if (wrapped.size < 2 || wrapped[0] != VERSION) return UnwrapResult.Corrupt
        val ivLength = wrapped[1].toInt()
        if (ivLength !in MIN_IV..MAX_IV || wrapped.size < 2 + ivLength + TAG_BYTES) return UnwrapResult.Corrupt
        val iv = wrapped.copyOfRange(2, 2 + ivLength)
        val sealed = wrapped.copyOfRange(2 + ivLength, wrapped.size)

        val key = keys.get() ?: return UnwrapResult.KeyLost
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, iv))
        } catch (e: InvalidKeyException) {
            // Includes KeyPermanentlyInvalidatedException.
            return UnwrapResult.KeyLost
        }
        cipher.updateAAD(AAD)
        return try {
            UnwrapResult.Ok(cipher.doFinal(sealed))
        } catch (e: AEADBadTagException) {
            UnwrapResult.Corrupt
        }
    }

    override fun deleteKey() = keys.delete()

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val TAG_BYTES = 16
        const val MIN_IV = 12
        const val MAX_IV = 16
        val AAD = "replyly:db-passphrase:v1".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * AES key in the Android Keystore. No user authentication (the accessibility service opens the DB
 * in the background, also while the screen is locked), so it is not invalidated by lock-screen
 * changes. It is deleted with the app's data (uninstall, "Clear storage").
 */
class AndroidKeystoreKeySource(
    private val alias: String,
) : SecretKeySource {
    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun get(): SecretKey? =
        try {
            keyStore().getKey(alias, null) as? SecretKey
        } catch (e: UnrecoverableKeyException) {
            null
        }

    override fun create(): SecretKey {
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(spec)
            generateKey()
        }
    }

    override fun delete() {
        val store = keyStore()
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}
