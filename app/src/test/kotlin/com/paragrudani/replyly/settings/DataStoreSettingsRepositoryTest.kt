package com.paragrudani.replyly.settings

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.testing.TestDataStores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreSettingsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val stores by lazy { TestDataStores(tmp.root) }

    @After fun tearDown() = stores.closeAll()

    @Test
    fun `a fresh install has the spec defaults`() =
        runBlocking<Unit> {
            val settings = DataStoreSettingsRepository(stores.open("settings")).current()
            assertThat(settings.endpoint).isEqualTo("https://openrouter.ai/api/v1")
            assertThat(settings.model).isEqualTo("anthropic/claude-haiku-5.5")
            assertThat(settings.historyMessageCount).isEqualTo(40)
            assertThat(settings.dailyRequestLimit).isEqualTo(200)
            assertThat(settings.retentionDays).isEqualTo(60)
            assertThat(settings.blockedPackages).isEmpty()
        }

    @Test
    fun `every field survives a restart`() =
        runBlocking<Unit> {
            val changed =
                ReplylySettings(
                    endpoint = "https://example.test/v1",
                    model = "google/gemini-3.8-flash",
                    historyMessageCount = 25,
                    aboutMe = "Engineer in Pune. Short replies, Hinglish with friends.",
                    blockedChatKeys = setOf("com.whatsapp:Bank OTP", "org.telegram.messenger:Work: HR"),
                    blockedPackages = setOf("com.google.android.apps.messaging"),
                    dailyRequestLimit = 50,
                    retentionDays = 30,
                )
            DataStoreSettingsRepository(stores.open("settings")).update { changed }

            assertThat(DataStoreSettingsRepository(stores.reopen("settings")).current()).isEqualTo(changed)
        }

    @Test
    fun `an emptied blocklist stays empty`() =
        runBlocking<Unit> {
            val repo = DataStoreSettingsRepository(stores.open("settings"))
            repo.update { it.copy(blockedPackages = setOf("com.a.b")) }
            repo.update { it.copy(blockedPackages = emptySet()) }
            assertThat(repo.current().blockedPackages).isEmpty()
        }

    @Test
    fun `concurrent updates are not lost`() =
        runBlocking<Unit> {
            val repo = DataStoreSettingsRepository(stores.open("settings"))
            (1..20)
                .map { i ->
                    async(Dispatchers.Default) {
                        repo.update {
                            it.copy(
                                blockedPackages =
                                    it.blockedPackages + "com.app$i.x",
                            )
                        }
                    }
                }.awaitAll()
            assertThat(repo.current().blockedPackages).hasSize(20)
        }
}
