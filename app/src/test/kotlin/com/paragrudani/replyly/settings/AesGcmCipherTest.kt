package com.paragrudani.replyly.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AesGcmCipherTest {
    private fun softwareKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = softwareKey()
    private val cipher = AesGcmCipher { key }
    private val secret = "sk-or-v1-0123456789abcdef".toByteArray()

    @Test
    fun `decrypts what it encrypted`() {
        assertThat(cipher.decrypt(cipher.encrypt(secret))).isEqualTo(secret)
    }

    @Test
    fun `the blob does not contain the plaintext and uses a fresh iv each time`() {
        val a = cipher.encrypt(secret)
        val b = cipher.encrypt(secret)
        assertThat(String(a, Charsets.ISO_8859_1)).doesNotContain("sk-or-v1")
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `a tampered blob is rejected`() {
        val blob = cipher.encrypt(secret)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt(blob) }
    }

    @Test
    fun `another key cannot decrypt it`() {
        val blob = cipher.encrypt(secret)
        val other = AesGcmCipher { softwareKey() }
        assertThrows(GeneralSecurityException::class.java) { other.decrypt(blob) }
    }

    @Test
    fun `a truncated blob is rejected`() {
        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt(ByteArray(5)) }
    }
}
