package com.paragrudani.replyly.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.testing.TestDataStores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
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
            assertThat(settings.useDynamicColor).isFalse()
            assertThat(settings.showBubble).isTrue()
            assertThat(settings.bubbleSize).isEqualTo(1.0f)
            assertThat(settings.bubbleOpacity).isEqualTo(100)
            assertThat(settings.shrinkWhenIdle).isFalse()
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
                    myNames = "Parag, Paru",
                    myXHandle = "kiran_test",
                    myXVoice = "dev, dry humour, no hashtags",
                    xPostLength = 500,
                    useDynamicColor = true,
                    showBubble = false,
                    bubbleSize = 1.15f,
                    bubbleOpacity = 60,
                    shrinkWhenIdle = true,
                )
            DataStoreSettingsRepository(stores.open("settings")).update { changed }

            assertThat(DataStoreSettingsRepository(stores.reopen("settings")).current()).isEqualTo(changed)
        }

    @Test
    fun `the migration deletes the removed parallel-mode key and keeps the rest`() =
        runBlocking<Unit> {
            // A store written by the old app: parallel mode on, plus a normal setting.
            stores.open("settings").edit {
                it[SettingsMigrations.PARALLEL_CALLS] = true
                it[intPreferencesKey("daily_request_limit")] = 77
            }

            val migrated = stores.reopen("settings", SettingsMigrations.ALL)
            val prefs = migrated.data.first()

            assertThat(prefs.contains(SettingsMigrations.PARALLEL_CALLS)).isFalse()
            assertThat(DataStoreSettingsRepository(migrated).current().dailyRequestLimit).isEqualTo(77)
            assertThat(SettingsMigrations.DropParallelCalls.shouldMigrate(prefs)).isFalse()
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

    @Test
    fun `out-of-range stored bubble values read as steps`() =
        runBlocking<Unit> {
            val cases =
                listOf(
                    Triple(2.0f, 250, 1.0f to 100),
                    Triple(0.5f, 0, 1.0f to 40),
                    Triple(1.3f, 55, 1.3f to 60),
                    Triple(0.85f, 39, 0.85f to 40),
                    Triple(-1f, -5, 1.0f to 40),
                )
            cases.forEachIndexed { i, (size, opacity, expected) ->
                val name = "bubble$i"
                stores.open(name).edit {
                    it[floatPreferencesKey("bubble_size")] = size
                    it[intPreferencesKey("bubble_opacity")] = opacity
                }
                val read = DataStoreSettingsRepository(stores.reopen(name)).current()
                assertThat(read.bubbleSize to read.bubbleOpacity).isEqualTo(expected)
            }
        }
}
