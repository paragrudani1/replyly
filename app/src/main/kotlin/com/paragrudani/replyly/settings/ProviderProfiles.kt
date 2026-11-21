package com.paragrudani.replyly.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/** Which wire protocol a profile speaks. */
@Serializable
enum class ApiFormat {
    /** OpenAI Chat Completions: `POST {baseUrl}/chat/completions`, `Authorization: Bearer`. */
    OpenAi,

    /** Anthropic Messages: `POST {baseUrl}/messages`, `x-api-key` and `anthropic-version`. */
    Anthropic,
}

/**
 * One saved LLM provider. Holds no secrets: the API key and the extra header *values* live
 * encrypted in [ProfileSecretStore], keyed by [id]. Header names are not secret and may be logged.
 */
@Serializable
data class ProviderProfile(
    val id: String,
    val name: String,
    val format: ApiFormat,
    /** Includes the version path, e.g. `https://openrouter.ai/api/v1` (see `llm/ProviderUrls`). */
    val baseUrl: String,
    val model: String,
    /** Names of the extra headers whose values are stored encrypted. */
    val extraHeaderNames: List<String> = emptyList(),
    /** Null: the generator's default (1024 for 3 suggestions, 512 per parallel call). */
    val maxTokens: Int? = null,
    /** Null: not sent. Only the OpenAI format sends it (Anthropic API version 2026-10-21 rejects it). */
    val temperature: Double? = null,
)

/** All profiles and the active one. [activeId] always names one of [profiles] when it is not empty. */
data class ProfilesState(
    val profiles: List<ProviderProfile>,
    val activeId: String?,
) {
    val active: ProviderProfile? get() = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
}

/**
 * Everything a request needs, resolved from a profile and its secrets. Kept in memory only for one
 * call. [toString] hides the key and header values.
 */
class ProviderConfig(
    val profile: ProviderProfile,
    /** Null when no key is saved (or it could not be decrypted). */
    val apiKey: String?,
    val extraHeaders: Map<String, String>,
) {
    override fun toString(): String =
        "ProviderConfig(profile=${profile.id}, format=${profile.format}, hasKey=${apiKey != null}, " +
            "headers=${extraHeaders.keys})"
}

/** Saved profiles plus the active one. Implementation: [DataStoreProfileRepository]. */
interface ProfileRepository {
    /** Runs the one-time migration from the single legacy config before its first value. */
    val state: Flow<ProfilesState>

    /** Inserts or replaces the profile with the same id (its secrets are left alone). */
    suspend fun upsert(profile: ProviderProfile)

    /** Deletes the profile and its secrets. The last profile cannot be deleted. */
    suspend fun delete(id: String)

    suspend fun setActive(id: String)

    /** The profile with its decrypted secrets, or null if there is no such profile. */
    suspend fun resolve(id: String): ProviderConfig?

    /** The active profile with its secrets, or null if there are no profiles. */
    suspend fun resolveActive(): ProviderConfig?
}

/**
 * Encrypted per-profile secrets: the API key and the extra header values. Never log what it
 * returns.
 */
interface ProfileSecretStore {
    fun hasApiKey(profileId: String): Flow<Boolean>

    suspend fun apiKey(profileId: String): String?

    /** Stores [key] trimmed; blank clears it. */
    suspend fun setApiKey(
        profileId: String,
        key: String,
    )

    /** Header name to value; empty if none are saved or they could not be decrypted. */
    suspend fun extraHeaders(profileId: String): Map<String, String>

    /** Replaces all extra header values of the profile; an empty map clears them. */
    suspend fun setExtraHeaders(
        profileId: String,
        headers: Map<String, String>,
    )

    suspend fun deleteAll(profileId: String)
}

/**
 * The legacy [ApiKeyStore] contract, now backed by the active profile's key, so onboarding and
 * anything else that asks "is there a key?" keeps working with profiles.
 */
class ActiveProfileApiKeyStore(
    private val profiles: ProfileRepository,
    private val secrets: ProfileSecretStore,
) : ApiKeyStore {
    @OptIn(ExperimentalCoroutinesApi::class)
    override val hasKey: Flow<Boolean> =
        profiles.state
            .map { it.active?.id }
            .distinctUntilChanged()
            .flatMapLatest { id -> if (id == null) flowOf(false) else secrets.hasApiKey(id) }
            .distinctUntilChanged()

    override suspend fun get(): String? =
        profiles.state
            .first()
            .active
            ?.let { secrets.apiKey(it.id) }

    override suspend fun set(key: String) {
        val id =
            profiles.state
                .first()
                .active
                ?.id ?: return
        secrets.setApiKey(id, key)
    }
}

/** In-memory fake for tests. Not persisted, not encrypted. */
class InMemoryProfileSecretStore : ProfileSecretStore {
    private val keys = MutableStateFlow<Map<String, String>>(emptyMap())
    private val headers = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())

    override fun hasApiKey(profileId: String): Flow<Boolean> = keys.map { profileId in it }

    override suspend fun apiKey(profileId: String): String? = keys.value[profileId]

    override suspend fun setApiKey(
        profileId: String,
        key: String,
    ) {
        val trimmed = key.trim()
        keys.update { if (trimmed.isEmpty()) it - profileId else it + (profileId to trimmed) }
    }

    override suspend fun extraHeaders(profileId: String): Map<String, String> = headers.value[profileId].orEmpty()

    override suspend fun setExtraHeaders(
        profileId: String,
        headers: Map<String, String>,
    ) {
        this.headers.update { if (headers.isEmpty()) it - profileId else it + (profileId to headers) }
    }

    override suspend fun deleteAll(profileId: String) {
        keys.update { it - profileId }
        headers.update { it - profileId }
    }
}

/** In-memory fake for tests. No migration. */
class InMemoryProfileRepository(
    initial: List<ProviderProfile>,
    activeId: String? = initial.firstOrNull()?.id,
    private val secrets: ProfileSecretStore = InMemoryProfileSecretStore(),
) : ProfileRepository {
    private val mutable = MutableStateFlow(ProfilesState(initial, activeId))
    override val state: StateFlow<ProfilesState> = mutable

    override suspend fun upsert(profile: ProviderProfile) =
        mutable.update { s ->
            val replaced = s.profiles.any { it.id == profile.id }
            val list =
                if (replaced) {
                    s.profiles.map {
                        if (it.id ==
                            profile.id
                        ) {
                            profile
                        } else {
                            it
                        }
                    }
                } else {
                    s.profiles + profile
                }
            s.copy(profiles = list, activeId = s.activeId ?: profile.id)
        }

    override suspend fun delete(id: String) {
        mutable.update { s ->
            if (s.profiles.size <= 1) return@update s
            val list = s.profiles.filterNot { it.id == id }
            s.copy(profiles = list, activeId = if (s.activeId == id) list.first().id else s.activeId)
        }
        secrets.deleteAll(id)
    }

    override suspend fun setActive(id: String) =
        mutable.update { s -> if (s.profiles.any { it.id == id }) s.copy(activeId = id) else s }

    override suspend fun resolve(id: String): ProviderConfig? {
        val profile = mutable.value.profiles.firstOrNull { it.id == id } ?: return null
        return ProviderConfig(profile, secrets.apiKey(id), secrets.extraHeaders(id))
    }

    override suspend fun resolveActive(): ProviderConfig? = mutable.value.active?.let { resolve(it.id) }
}
