package com.paragrudani.replyly.ui.settings

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.cost.CostGuard
import com.paragrudani.replyly.cost.InMemoryCostGuard
import com.paragrudani.replyly.feedback.InMemoryFeedbackLog
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.llm.TokenUsage
import com.paragrudani.replyly.parser.ChatKey
import com.paragrudani.replyly.settings.InMemoryApiKeyStore
import com.paragrudani.replyly.settings.InMemorySettingsRepository
import com.paragrudani.replyly.settings.ReplylySettings
import com.paragrudani.replyly.settings.SettingsRepository
import com.paragrudani.replyly.settings.current
import com.paragrudani.replyly.testing.MutableClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val clock = MutableClock.atLocal(LocalDateTime.of(2026, 11, 21, 10, 0))
    private val settings = InMemorySettingsRepository(ReplylySettings(retentionDays = 60))
    private val apiKeys = InMemoryApiKeyStore()
    private val cost = InMemoryCostGuard(dailyRequestLimit = 200)
    private val feedback = InMemoryFeedbackLog(clock)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SettingsViewModel(settings, apiKeys, cost, feedback, clock)

    @Test
    fun `the form starts from the stored settings`() =
        runTest {
            settings.update { it.copy(model = "google/gemini-3.8-flash") }
            val state = viewModel().state.value
            assertThat(state.draft?.model).isEqualTo("google/gemini-3.8-flash")
            assertThat(state.dirty).isFalse()
        }

    @Test
    fun `saving a valid form writes it and clears dirty`() =
        runTest {
            val vm = viewModel()
            vm.editDraft { it.copy(model = "~openai/gpt-mini-latest", dailyRequestLimit = "50") }
            assertThat(vm.state.value.dirty).isTrue()

            vm.save()

            assertThat(settings.current().model).isEqualTo("~openai/gpt-mini-latest")
            assertThat(settings.current().dailyRequestLimit).isEqualTo(50)
            assertThat(vm.state.value.dirty).isFalse()
            assertThat(vm.state.value.errors).isEmpty()
        }

    @Test
    fun `an invalid form is not saved and shows the errors`() =
        runTest {
            val vm = viewModel()
            vm.editDraft { it.copy(endpoint = "http://insecure.test", retentionDays = "0") }
            vm.save()

            assertThat(settings.current()).isEqualTo(ReplylySettings(retentionDays = 60))
            assertThat(vm.state.value.errors.keys).containsExactly(SettingsField.Endpoint, SettingsField.RetentionDays)
        }

    @Test
    fun `the api key is saved, never shown back, and can be cleared`() =
        runTest {
            val vm = viewModel()
            assertThat(vm.state.value.apiKeySaved).isFalse()

            vm.setApiKeyInput("sk-or-v1-0123456789abcdef")
            vm.saveApiKey()

            assertThat(apiKeys.get()).isEqualTo("sk-or-v1-0123456789abcdef")
            assertThat(vm.state.value.apiKeySaved).isTrue()
            assertThat(vm.state.value.apiKeyInput).isEmpty()
            assertThat(vm.state.value.toString()).doesNotContain("0123456789abcdef")

            vm.clearApiKey()
            assertThat(apiKeys.get()).isNull()
            assertThat(vm.state.value.apiKeySaved).isFalse()
        }

    @Test
    fun `replacing a saved key keeps the field open until the new key is stored`() =
        runTest {
            val vm = viewModel()
            vm.setApiKeyInput("sk-or-v1-0123456789abcdef")
            vm.saveApiKey()
            assertThat(vm.state.value.showApiKeyField).isFalse()

            vm.startReplacingApiKey()
            assertThat(vm.state.value.showApiKeyField).isTrue()
            vm.setApiKeyInput("bad key")
            vm.saveApiKey()
            assertThat(vm.state.value.showApiKeyField).isTrue()
            assertThat(vm.state.value.apiKeyError).isNotNull()

            vm.setApiKeyInput("sk-or-v1-fedcba9876543210")
            vm.saveApiKey()
            assertThat(vm.state.value.showApiKeyField).isFalse()
            assertThat(apiKeys.get()).isEqualTo("sk-or-v1-fedcba9876543210")
        }

    @Test
    fun `cancelling a replacement keeps the old key`() =
        runTest {
            val vm = viewModel()
            vm.setApiKeyInput("sk-or-v1-0123456789abcdef")
            vm.saveApiKey()
            vm.startReplacingApiKey()
            vm.setApiKeyInput("sk-or-v1-half")
            vm.cancelReplacingApiKey()
            assertThat(vm.state.value.showApiKeyField).isFalse()
            assertThat(vm.state.value.apiKeyInput).isEmpty()
            assertThat(apiKeys.get()).isEqualTo("sk-or-v1-0123456789abcdef")
        }

    @Test
    fun `a bad api key is refused with a message that does not echo it`() =
        runTest {
            val vm = viewModel()
            vm.setApiKeyInput("sk 123")
            vm.saveApiKey()
            assertThat(apiKeys.get()).isNull()
            assertThat(vm.state.value.apiKeyError).isNotNull()
            assertThat(vm.state.value.apiKeyError).doesNotContain("sk 123")
        }

    @Test
    fun `blocklist changes are stored at once`() =
        runTest {
            val vm = viewModel()
            assertThat(vm.addBlockedPackage(" com.bank.app ")).isNull()
            assertThat(vm.addBlockedChat("com.whatsapp:Bank OTP")).isNull()
            assertThat(settings.current().blockedPackages).containsExactly("com.bank.app")
            assertThat(settings.current().blockedChatKeys).containsExactly("com.whatsapp:Bank OTP")
            assertThat(
                vm.state.value.draft
                    ?.blockedPackages,
            ).containsExactly("com.bank.app")

            vm.removeBlockedPackage("com.bank.app")
            assertThat(settings.current().blockedPackages).isEmpty()
        }

    @Test
    fun `a blocklist change keeps unsaved edits in the form`() =
        runTest {
            val vm = viewModel()
            vm.editDraft { it.copy(aboutMe = "typing...") }
            vm.addBlockedPackage("com.bank.app")
            assertThat(
                vm.state.value.draft
                    ?.aboutMe,
            ).isEqualTo("typing...")
            assertThat(settings.current().aboutMe).isEmpty()
        }

    @Test
    fun `a failed write shows a fixed message instead of crashing`() =
        runTest {
            val backing = settings
            val failing =
                object : SettingsRepository {
                    var fail = true
                    override val settings = backing.settings

                    override suspend fun update(transform: (ReplylySettings) -> ReplylySettings) {
                        if (fail) throw IOException("disk full at /data/secret/path")
                        backing.update(transform)
                    }
                }
            val vm = SettingsViewModel(failing, apiKeys, cost, feedback, clock)

            vm.editDraft { it.copy(dailyRequestLimit = "50") }
            vm.save()
            assertThat(vm.state.value.saveError).isEqualTo("Could not save settings")
            assertThat(vm.state.value.dirty).isTrue() // the edit stays so the user can retry
            assertThat(settings.current().dailyRequestLimit).isEqualTo(200)

            vm.dismissSaveError()
            assertThat(vm.addBlockedPackage("com.bank.app")).isNull()
            assertThat(vm.state.value.saveError).isEqualTo("Could not save settings")
            vm.dismissSaveError()
            vm.removeBlockedChat("com.whatsapp:Mom")
            assertThat(vm.state.value.saveError).isEqualTo("Could not save settings")

            vm.dismissSaveError()
            failing.fail = false
            vm.save()
            assertThat(vm.state.value.saveError).isNull()
            assertThat(settings.current().dailyRequestLimit).isEqualTo(50)
        }

    @Test
    fun `refreshUsage asks the cost guard to recompute`() =
        runTest {
            var refreshed = 0
            val guard =
                object : CostGuard by cost {
                    override fun refresh() {
                        refreshed++
                    }
                }
            SettingsViewModel(settings, apiKeys, guard, feedback, clock).refreshUsage()
            assertThat(refreshed).isEqualTo(1)
        }

    @Test
    fun `invalid blocklist entries are refused`() =
        runTest {
            val vm = viewModel()
            assertThat(vm.addBlockedPackage("bank")).isNotNull()
            assertThat(vm.addBlockedChat("Mom")).isNotNull()
            assertThat(settings.current().blockedPackages).isEmpty()
            assertThat(settings.current().blockedChatKeys).isEmpty()
        }

    @Test
    fun `usage shows requests today against the limit and tokens this month`() =
        runTest {
            val vm = viewModel()
            cost.tryAcquire()
            cost.tryAcquire()
            cost.recordUsage(TokenUsage(1500, 120))
            with(vm.state.value.usage) {
                assertThat(requestsToday).isEqualTo(2)
                assertThat(dailyRequestLimit).isEqualTo(200)
                assertThat(totalTokensThisMonth).isEqualTo(1620)
            }
        }

    @Test
    fun `opening settings purges feedback past retention, and clear empties the log`() =
        runTest {
            val chat = ChatKey("com.whatsapp", "Riya")
            val three = listOf(Suggestion("a", "1"), Suggestion("b", "2"), Suggestion("c", "3"))
            feedback.record(chat, "m", "old", three)
            clock.advance(Duration.ofDays(61))
            feedback.record(chat, "m", "new", three)

            val vm = viewModel()
            assertThat(vm.feedbackEntries.first().map { it.prompt }).containsExactly("new")

            vm.clearFeedbackLog()
            assertThat(vm.feedbackEntries.first()).isEmpty()
        }
}
