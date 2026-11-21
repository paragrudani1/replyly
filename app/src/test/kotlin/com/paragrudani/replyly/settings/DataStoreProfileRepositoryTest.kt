package com.paragrudani.replyly.settings

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.testing.TestDataStores
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Profiles, their encrypted secrets, and the one-time migration from the single legacy config. */
class DataStoreProfileRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val stores by lazy { TestDataStores(tmp.root) }
    private var key: SecretKey = newKey()
    private val legacyKeyValue = "sk-or-v1-legacy0123456789"
    private val legacySettings =
        InMemorySettingsRepository(
            ReplylySettings(endpoint = "https://openrouter.ai/api/v1", model = "google/gemini-3.8-flash"),
        )
    private var ids = 0

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private lateinit var legacy: EncryptedApiKeyStore
    private lateinit var secrets: EncryptedProfileSecretStore

    private fun open(): DataStoreProfileRepository {
        legacy = EncryptedApiKeyStore(stores.open(ReplylyDataStores.API_KEY), AesGcmCipher { key })
        secrets = EncryptedProfileSecretStore(stores.open(ReplylyDataStores.PROFILE_SECRETS), { key })
        return DataStoreProfileRepository(
            stores.open(ReplylyDataStores.PROFILES),
            secrets,
            legacySettings,
            legacy,
            newId = { "id-${++ids}" },
        )
    }

    /** Simulates a process restart: every store is shut down and opened again. */
    private suspend fun reopen(): DataStoreProfileRepository {
        legacy = EncryptedApiKeyStore(stores.reopen(ReplylyDataStores.API_KEY), AesGcmCipher { key })
        secrets = EncryptedProfileSecretStore(stores.reopen(ReplylyDataStores.PROFILE_SECRETS), { key })
        return DataStoreProfileRepository(
            stores.reopen(ReplylyDataStores.PROFILES),
            secrets,
            legacySettings,
            legacy,
            newId = { "id-${++ids}" },
        )
    }

    @After fun tearDown() = stores.closeAll()

    @Test
    fun `migration turns the single config into an active OpenRouter profile with the same key and model`() =
        runBlocking<Unit> {
            val repo = open()
            legacy.set(legacyKeyValue)

            val state = repo.state.first()

            assertThat(state.profiles).hasSize(1)
            val profile = state.active!!
            assertThat(profile.name).isEqualTo("OpenRouter")
            assertThat(profile.format).isEqualTo(ApiFormat.OpenAi)
            assertThat(profile.baseUrl).isEqualTo("https://openrouter.ai/api/v1")
            assertThat(profile.model).isEqualTo("google/gemini-3.8-flash")
            assertThat(state.activeId).isEqualTo(profile.id)
            val resolved = repo.resolveActive()!!
            assertThat(resolved.apiKey).isEqualTo(legacyKeyValue)
            assertThat(resolved.extraHeaders).isEmpty()
            // The legacy key stays, so an older build still works after a downgrade.
            assertThat(legacy.get()).isEqualTo(legacyKeyValue)
        }

    @Test
    fun `migration is idempotent across reads, restarts and a changed legacy key`() =
        runBlocking<Unit> {
            open().also { legacy.set(legacyKeyValue) }.state.first()

            val again = reopen()
            legacy.set("sk-or-v1-changed-later-999")
            val state = again.state.first()

            assertThat(state.profiles.map { it.id }).containsExactly("id-1")
            assertThat(again.resolveActive()!!.apiKey).isEqualTo(legacyKeyValue)
            assertThat(again.state.first()).isEqualTo(state)
        }

    @Test
    fun `concurrent first reads migrate once`() =
        runBlocking<Unit> {
            val repo = open()
            legacy.set(legacyKeyValue)
            val states = List(8) { async { repo.state.first() } }.awaitAll()
            assertThat(states.map { it.profiles.size }.toSet()).containsExactly(1)
            assertThat(ids).isEqualTo(1)
        }

    @Test
    fun `fresh install without a legacy key gets a default OpenRouter profile without a key`() =
        runBlocking<Unit> {
            val repo =
                DataStoreProfileRepository(
                    stores.open(ReplylyDataStores.PROFILES),
                    InMemoryProfileSecretStore(),
                    InMemorySettingsRepository(),
                    InMemoryApiKeyStore(),
                    newId = { "only" },
                )
            val state = repo.state.first()
            assertThat(state.active!!.model).isEqualTo(ReplylySettings.DEFAULT_MODEL)
            assertThat(state.active!!.name).isEqualTo("OpenRouter")
            assertThat(repo.resolveActive()!!.apiKey).isNull()
        }

    @Test
    fun `a non-OpenRouter legacy endpoint is migrated as Custom`() =
        runBlocking<Unit> {
            val repo =
                DataStoreProfileRepository(
                    stores.open(ReplylyDataStores.PROFILES),
                    InMemoryProfileSecretStore(),
                    InMemorySettingsRepository(ReplylySettings(endpoint = "https://llm.example.com/v1")),
                    InMemoryApiKeyStore(),
                )
            assertThat(
                repo.state
                    .first()
                    .active!!
                    .name,
            ).isEqualTo("Custom")
        }

    @Test
    fun `a write before the first read still migrates first`() =
        runBlocking<Unit> {
            val repo = open()
            legacy.set(legacyKeyValue)
            repo.upsert(
                ProviderProfile(
                    "new",
                    "Anthropic",
                    ApiFormat.Anthropic,
                    "https://api.anthropic.com/v1",
                    "claude-haiku-5-5",
                ),
            )
            val state = repo.state.first()
            assertThat(state.profiles.map { it.id }).containsExactly("id-1", "new").inOrder()
            assertThat(state.activeId).isEqualTo("id-1")
        }

    @Test
    fun `switching, editing and deleting profiles`() =
        runBlocking<Unit> {
            val repo = open()
            val anthropic =
                ProviderProfile(
                    "a",
                    "Anthropic",
                    ApiFormat.Anthropic,
                    "https://api.anthropic.com/v1",
                    "claude-haiku-5-5",
                )
            repo.upsert(anthropic)
            secrets.setApiKey("a", "sk-ant-key-123456")
            secrets.setExtraHeaders("a", mapOf("X-Org" to "org-1"))

            repo.state.test {
                assertThat(awaitItem().activeId).isEqualTo("id-1")
                repo.setActive("a")
                assertThat(awaitItem().active).isEqualTo(anthropic)
                repo.upsert(anthropic.copy(model = "claude-sonnet-5-5"))
                assertThat(awaitItem().active!!.model).isEqualTo("claude-sonnet-5-5")
                repo.delete("a")
                val afterDelete = awaitItem()
                assertThat(afterDelete.profiles.map { it.id }).containsExactly("id-1")
                assertThat(afterDelete.activeId).isEqualTo("id-1")
            }
            assertThat(secrets.apiKey("a")).isNull()
            assertThat(secrets.extraHeaders("a")).isEmpty()

            // The last profile stays.
            repo.delete("id-1")
            assertThat(repo.state.first().profiles).hasSize(1)
        }

    @Test
    fun `secrets are encrypted on disk and bound to their profile`() =
        runBlocking<Unit> {
            val repo = open()
            repo.state.first()
            secrets.setApiKey("id-1", "sk-plain-key-0001")
            secrets.setExtraHeaders("id-1", mapOf("X-Proxy-Token" to "header-secret-42"))
            assertThat(repo.resolveActive()!!.extraHeaders).containsExactly("X-Proxy-Token", "header-secret-42")
            stores.closeAll()

            val secretsFile = stores.file(ReplylyDataStores.PROFILE_SECRETS).readBytes().toString(Charsets.ISO_8859_1)
            assertThat(secretsFile).doesNotContain("sk-plain-key-0001")
            assertThat(secretsFile).doesNotContain("header-secret-42")
            val profilesFile = stores.file(ReplylyDataStores.PROFILES).readBytes().toString(Charsets.ISO_8859_1)
            assertThat(profilesFile).doesNotContain("sk-plain")
            assertThat(profilesFile).doesNotContain("header-secret")
        }

    @Test
    fun `a blob from another profile does not decrypt`() =
        runBlocking<Unit> {
            val store = EncryptedProfileSecretStore(stores.open(ReplylyDataStores.PROFILE_SECRETS), { key })
            store.setApiKey("a", "sk-key-for-a-000")
            stores.closeAll()
            // Copy a's blob into b's slot by rewriting the file bytes.
            val file = stores.file(ReplylyDataStores.PROFILE_SECRETS)
            val bytes = file.readBytes()
            val text = bytes.toString(Charsets.ISO_8859_1)
            file.writeBytes(text.replace("key_a", "key_b").toByteArray(Charsets.ISO_8859_1))
            val reopened = EncryptedProfileSecretStore(stores.reopen(ReplylyDataStores.PROFILE_SECRETS), { key })
            assertThat(reopened.apiKey("b")).isNull()
        }

    @Test
    fun `a lost Keystore key reads as no key and drops the blob`() =
        runBlocking<Unit> {
            val repo = open()
            repo.state.first()
            secrets.setApiKey("id-1", "sk-plain-key-0001")
            key = newKey()
            assertThat(secrets.apiKey("id-1")).isNull()
            assertThat(secrets.hasApiKey("id-1").first()).isFalse()
        }

    @Test
    fun `the active-profile key store follows the active profile`() =
        runBlocking<Unit> {
            val repo = open()
            legacy.set(legacyKeyValue)
            val store = ActiveProfileApiKeyStore(repo, secrets)
            assertThat(store.get()).isEqualTo(legacyKeyValue)
            assertThat(store.hasKey.first()).isTrue()

            repo.upsert(ProviderProfile("b", "B", ApiFormat.OpenAi, "https://api.openai.com/v1", "m"))
            repo.setActive("b")
            assertThat(store.hasKey.first()).isFalse()
            store.set("sk-openai-key-0001")
            assertThat(secrets.apiKey("b")).isEqualTo("sk-openai-key-0001")
            assertThat(secrets.apiKey("id-1")).isEqualTo(legacyKeyValue)
        }

    @Test
    fun `config toString hides the key and header values`() {
        val config =
            ProviderConfig(
                ProviderProfile("x", "X", ApiFormat.OpenAi, "https://a.b/v1", "m", listOf("X-Org")),
                "sk-secret-000",
                mapOf("X-Org" to "value-secret"),
            )
        assertThat(config.toString()).doesNotContain("sk-secret")
        assertThat(config.toString()).doesNotContain("value-secret")
        assertThat(config.toString()).contains("X-Org")
    }
}
