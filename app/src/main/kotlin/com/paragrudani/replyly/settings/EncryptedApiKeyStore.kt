package com.paragrudani.replyly.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64

/**
 * [ApiKeyStore] keeping only the AES-GCM ciphertext (Base64) in its own DataStore file. The
 * plain key exists only in memory while a caller holds it. Never log it or the blob.
 *
 * If the Keystore key is gone (reinstall, cleared credentials) the blob cannot be decrypted: it is
 * deleted and the store reads as "no key", so onboarding and settings ask for it again.
 */
class EncryptedApiKeyStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: AesGcmCipher,
    private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ApiKeyStore {
    override val hasKey: Flow<Boolean> = dataStore.data.map { it[BLOB] != null }.distinctUntilChanged()

    override suspend fun get(): String? {
        val encoded = dataStore.data.first()[BLOB] ?: return null
        val plain =
            withContext(cryptoDispatcher) {
                try {
                    String(cipher.decrypt(Base64.getDecoder().decode(encoded)), Charsets.UTF_8)
                } catch (_: GeneralSecurityException) {
                    null
                } catch (_: ProviderException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        if (plain == null) {
            // Only drop the blob we failed on, not one a concurrent set() just wrote.
            dataStore.edit { if (it[BLOB] == encoded) it.remove(BLOB) }
        }
        return plain
    }

    override suspend fun set(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            dataStore.edit { it.remove(BLOB) }
            return
        }
        val encoded =
            withContext(cryptoDispatcher) {
                Base64.getEncoder().encodeToString(cipher.encrypt(trimmed.toByteArray(Charsets.UTF_8)))
            }
        dataStore.edit { it[BLOB] = encoded }
    }

    private companion object {
        val BLOB = stringPreferencesKey("api_key_blob")
    }
}
