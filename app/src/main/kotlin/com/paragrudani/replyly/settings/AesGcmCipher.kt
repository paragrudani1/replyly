package com.paragrudani.replyly.settings

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM over small secrets. [key] supplies the key: the Android Keystore key in the app
 * ([AndroidKeystoreKeys]), a software key in tests (Robolectric has no Keystore).
 *
 * Blob layout: `version (1) | iv (12) | ciphertext + tag (16)`. The IV comes from the cipher
 * (the Keystore insists on generating it). Error messages never contain the data.
 */
class AesGcmCipher(
    private val associatedData: ByteArray = DEFAULT_AAD,
    private val key: () -> SecretKey,
) {
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData)
        val iv = cipher.iv
        if (iv.size != IV_BYTES) throw GeneralSecurityException("Unexpected IV size")
        val sealed = cipher.doFinal(plaintext)
        return ByteBuffer
            .allocate(1 + IV_BYTES + sealed.size)
            .put(VERSION)
            .put(iv)
            .put(sealed)
            .array()
    }

    fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size < 1 + IV_BYTES + TAG_BITS / 8 || blob[0] != VERSION) {
            throw GeneralSecurityException("Not an encrypted blob")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 1, IV_BYTES))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 1
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val DEFAULT_AAD = "replyly.api_key".toByteArray()
    }
}
