package com.paragrudani.replyly.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.UUID

/**
 * [ProfileRepository] on its own Preferences DataStore: the profile list as JSON (no secrets) and
 * the active id. Secrets go to [secrets].
 *
 * **Migration.** Before the first read or write, if no profile list was ever stored, the single
 * legacy config ([ReplylySettings.endpoint], [ReplylySettings.model] and the [legacyKey]) becomes
 * one OpenAI-format profile, named "OpenRouter" (or "Custom" when the endpoint is not OpenRouter's),
 * and is made active. Idempotent: it runs once per process at most, and the transaction that
 * stores the list only writes if the list is still absent, so a second run (another process,
 * a restart) changes nothing. The legacy key is copied, not deleted, so an older build still works.
 * If encrypting the copy fails (Keystore), nothing is written and the migration runs again on the
 * next read; until then the list is empty.
 */
class DataStoreProfileRepository(
    private val dataStore: DataStore<Preferences>,
    private val secrets: ProfileSecretStore,
    private val legacySettings: SettingsRepository,
    private val legacyKey: ApiKeyStore,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ProfileRepository {
    private val migration = Mutex()

    @Volatile private var migrated = false

    override val state: Flow<ProfilesState> =
        flow {
            ensureMigrated()
            emitAll(dataStore.data.map { it.toState() })
        }.distinctUntilChanged()

    override suspend fun upsert(profile: ProviderProfile) {
        ensureMigrated()
        dataStore.edit { prefs ->
            val list = prefs.profiles()
            val updated =
                if (list.any { it.id == profile.id }) {
                    list.map {
                        if (it.id ==
                            profile.id
                        ) {
                            profile
                        } else {
                            it
                        }
                    }
                } else {
                    list + profile
                }
            prefs[PROFILES] = encode(updated)
            if (prefs[ACTIVE] == null || list.none { it.id == prefs[ACTIVE] }) prefs[ACTIVE] = profile.id
        }
    }

    override suspend fun delete(id: String) {
        ensureMigrated()
        var deleted = false
        dataStore.edit { prefs ->
            val list = prefs.profiles()
            if (list.size <= 1 || list.none { it.id == id }) return@edit
            val remaining = list.filterNot { it.id == id }
            prefs[PROFILES] = encode(remaining)
            if (prefs[ACTIVE] == id) prefs[ACTIVE] = remaining.first().id
            deleted = true
        }
        if (deleted) secrets.deleteAll(id)
    }

    override suspend fun setActive(id: String) {
        ensureMigrated()
        dataStore.edit { prefs -> if (prefs.profiles().any { it.id == id }) prefs[ACTIVE] = id }
    }

    override suspend fun resolve(id: String): ProviderConfig? {
        val profile = state.first().profiles.firstOrNull { it.id == id } ?: return null
        return ProviderConfig(profile, secrets.apiKey(id), secrets.extraHeaders(id))
    }

    override suspend fun resolveActive(): ProviderConfig? = state.first().active?.let { resolve(it.id) }

    private suspend fun ensureMigrated() {
        if (migrated) return
        migration.withLock {
            if (migrated) return
            if (dataStore.data.first()[PROFILES] == null && !migrate()) return
            migrated = true
        }
    }

    /** False when the key could not be copied: nothing is written and the next read tries again. */
    private suspend fun migrate(): Boolean {
        val legacy = legacySettings.settings.first()
        val profile =
            ProviderProfile(
                id = newId(),
                name = if (isOpenRouter(legacy.endpoint)) "OpenRouter" else "Custom",
                format = ApiFormat.OpenAi,
                baseUrl = legacy.endpoint,
                model = legacy.model,
            )
        val key =
            try {
                legacyKey.get()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // A Keystore failure: the user enters the key again on the profile.
            }
        if (!key.isNullOrBlank()) {
            try {
                secrets.setApiKey(profile.id, key)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Encrypting failed (Keystore). The legacy key stays where it is; skip for now.
                runCatching { secrets.deleteAll(profile.id) }
                return false
            }
        }
        var written = false
        dataStore.edit { prefs ->
            if (prefs[PROFILES] == null) {
                prefs[PROFILES] = encode(listOf(profile))
                prefs[ACTIVE] = profile.id
                written = true
            }
        }
        // Another writer stored a list first: drop the copy we made.
        if (!written) secrets.deleteAll(profile.id)
        return true
    }

    private fun Preferences.profiles(): List<ProviderProfile> =
        this[PROFILES]?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }.orEmpty()

    private fun Preferences.toState(): ProfilesState {
        val list = profiles()
        val active = this[ACTIVE]?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
        return ProfilesState(list, active)
    }

    private fun encode(list: List<ProviderProfile>) = json.encodeToString(listSerializer, list)

    private companion object {
        val PROFILES = stringPreferencesKey("profiles_json")
        val ACTIVE = stringPreferencesKey("active_profile_id")
        val json = Json { ignoreUnknownKeys = true }
        val listSerializer = ListSerializer(ProviderProfile.serializer())

        fun isOpenRouter(endpoint: String): Boolean {
            val host = runCatching { URI(endpoint.trim()).host }.getOrNull() ?: return false
            return host == "openrouter.ai" || host.endsWith(".openrouter.ai")
        }
    }
}
