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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64
import javax.crypto.SecretKey

/**
 * [ProfileSecretStore] with the same scheme as [EncryptedApiKeyStore]: AES-256-GCM under the
 * Keystore key, Base64 ciphertext in its own DataStore file, plaintext never stored. Each blob's
 * associated data names its profile id and kind, so a blob copied to another profile or slot
 * does not decrypt. A blob that cannot be decrypted (Keystore key gone) is deleted and reads as
 * absent, like the legacy key store.
 */
class EncryptedProfileSecretStore(
    private val dataStore: DataStore<Preferences>,
    private val key: () -> SecretKey,
    private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProfileSecretStore {
    override fun hasApiKey(profileId: String): Flow<Boolean> =
        dataStore.data.map { it[keySlot(profileId)] != null }.distinctUntilChanged()

    override suspend fun apiKey(profileId: String): String? =
        read(keySlot(profileId), aad(profileId, KIND_KEY))?.let { String(it, Charsets.UTF_8) }

    override suspend fun setApiKey(
        profileId: String,
        key: String,
    ) = write(keySlot(profileId), aad(profileId, KIND_KEY), key.trim().takeIf { it.isNotEmpty() })

    override suspend fun extraHeaders(profileId: String): Map<String, String> {
        val plain = read(headerSlot(profileId), aad(profileId, KIND_HEADERS)) ?: return emptyMap()
        return runCatching { json.decodeFromString(headerMap, String(plain, Charsets.UTF_8)) }.getOrDefault(emptyMap())
    }

    override suspend fun setExtraHeaders(
        profileId: String,
        headers: Map<String, String>,
    ) = write(
        headerSlot(profileId),
        aad(profileId, KIND_HEADERS),
        headers.takeIf { it.isNotEmpty() }?.let { json.encodeToString(headerMap, it) },
    )

    override suspend fun deleteAll(profileId: String) {
        dataStore.edit {
            it.remove(keySlot(profileId))
            it.remove(headerSlot(profileId))
        }
    }

    private suspend fun read(
        slot: Preferences.Key<String>,
        aad: ByteArray,
    ): ByteArray? {
        val encoded = dataStore.data.first()[slot] ?: return null
        val plain =
            withContext(cryptoDispatcher) {
                try {
                    AesGcmCipher(aad, key).decrypt(Base64.getDecoder().decode(encoded))
                } catch (_: GeneralSecurityException) {
                    null
                } catch (_: ProviderException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        // Only drop the blob we failed on, not one a concurrent write just stored.
        if (plain == null) dataStore.edit { if (it[slot] == encoded) it.remove(slot) }
        return plain
    }

    private suspend fun write(
        slot: Preferences.Key<String>,
        aad: ByteArray,
        plain: String?,
    ) {
        if (plain == null) {
            dataStore.edit { it.remove(slot) }
            return
        }
        val encoded =
            withContext(cryptoDispatcher) {
                Base64.getEncoder().encodeToString(AesGcmCipher(aad, key).encrypt(plain.toByteArray(Charsets.UTF_8)))
            }
        dataStore.edit { it[slot] = encoded }
    }

    private companion object {
        const val KIND_KEY = "api_key"
        const val KIND_HEADERS = "headers"
        val json = Json
        val headerMap = MapSerializer(String.serializer(), String.serializer())

        fun keySlot(id: String) = stringPreferencesKey("key_$id")

        fun headerSlot(id: String) = stringPreferencesKey("headers_$id")

        fun aad(
            id: String,
            kind: String,
        ) = "replyly.profile.$id.$kind".toByteArray()
    }
}
