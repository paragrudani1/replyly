package com.paragrudani.replyly.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Non-exportable AES-256-GCM keys in the Android Keystore. The key stays in the Keystore across
 * app updates signed with the same certificate; uninstalling the app deletes it.
 * Device-only: Robolectric has no AndroidKeyStore provider (see docs/DEVICE_CHECKLIST.md, steps 2.3 and 10.10).
 */
object AndroidKeystoreKeys {
    const val API_KEY_ALIAS = "replyly_api_key_v1"
    private const val PROVIDER = "AndroidKeyStore"

    @Synchronized
    fun getOrCreateAesKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }
}
