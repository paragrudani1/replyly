package com.paragrudani.replyly.ui.settings

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.cost.CostGuard
import com.paragrudani.replyly.cost.InMemoryCostGuard
import com.paragrudani.replyly.feedback.FeedbackEntry
import com.paragrudani.replyly.feedback.FeedbackLog
import com.paragrudani.replyly.feedback.InMemoryFeedbackLog
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.llm.TokenUsage
import com.paragrudani.replyly.parser.ChatKey
import com.paragrudani.replyly.security.DatabaseKeyUnavailableException
import com.paragrudani.replyly.settings.InMemorySettingsRepository
import com.paragrudani.replyly.settings.ReplylySettings
import com.paragrudani.replyly.settings.SettingsRepository
import com.paragrudani.replyly.settings.current
import com.paragrudani.replyly.settings.myNameList
import com.paragrudani.replyly.testing.MutableClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val clock = MutableClock.atLocal(LocalDateTime.of(2026, 11, 21, 10, 0))
    private val settings = InMemorySettingsRepository(ReplylySettings(retentionDays = 60))
    private val cost = InMemoryCostGuard(dailyRequestLimit = 200)
    private val feedback = InMemoryFeedbackLog(clock)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SettingsViewModel(settings, cost, feedback, clock)

    @Test
    fun `the form starts from the stored settings`() =
        runTest {
            settings.update { it.copy(aboutMe = "Short replies.") }
            val state = viewModel().state.value
            assertThat(state.draft?.aboutMe).isEqualTo("Short replies.")
            assertThat(state.dirty).isFalse()
        }

    @Test
    fun `saving a valid form writes it and clears dirty`() =
        runTest {
            val vm = viewModel()
            vm.editDraft { it.copy(aboutMe = "Short replies.", dailyRequestLimit = "50") }
            assertThat(vm.state.value.dirty).isTrue()

            vm.save()

            assertThat(settings.current().aboutMe).isEqualTo("Short replies.")
            assertThat(settings.current().dailyRequestLimit).isEqualTo(50)
            assertThat(vm.state.value.dirty).isFalse()
            assertThat(vm.state.value.errors).isEmpty()
        }

    @Test
    fun `savedCount goes up only when Save wrote the settings`() =
        runTest {
            val vm = viewModel()
            assertThat(vm.state.value.savedCount).isEqualTo(0)
            vm.editDraft { it.copy(dailyRequestLimit = "0") }
            vm.save()
            assertThat(vm.state.value.savedCount).isEqualTo(0) // invalid: nothing written
            vm.editDraft { it.copy(dailyRequestLimit = "50") }
            vm.save()
            assertThat(vm.state.value.savedCount).isEqualTo(1)
        }

    @Test
    fun `names people call me are saved with the form, cleaned`() =
        runTest {
            val vm = viewModel()
            assertThat(
                vm.state.value.draft
                    ?.myNames,
            ).isEmpty()

            vm.editDraft { it.copy(myNames = " Parag ,  Paru,, ") }
            assertThat(vm.state.value.dirty).isTrue()
            vm.save()

            assertThat(settings.current().myNames).isEqualTo("Parag, Paru")
            assertThat(settings.current().myNameList()).containsExactly("Parag", "Paru").inOrder()
        }

    @Test
    fun `my X handle is saved without the @ and shown with it`() =
        runTest {
            val vm = viewModel()
            assertThat(
                vm.state.value.draft
                    ?.myXHandle,
            ).isEmpty()

            vm.editDraft { it.copy(myXHandle = "  @Kiran_Test ") }
            vm.save()

            assertThat(settings.current().myXHandle).isEqualTo("Kiran_Test")
            assertThat(
                vm.state.value.draft
                    ?.myXHandle,
            ).isEqualTo("@Kiran_Test")
            assertThat(vm.state.value.dirty).isFalse()

            vm.editDraft { it.copy(myXHandle = "") }
            vm.save()
            assertThat(settings.current().myXHandle).isEmpty()
        }

    @Test
    fun `an X handle with spaces or over 15 characters is an error`() =
        runTest {
            val vm = viewModel()
            for (bad in listOf("kiran test", "a_handle_that_is_too_long", "x.com/kiran")) {
                vm.editDraft { it.copy(myXHandle = bad) }
                vm.save()
                assertThat(vm.state.value.errors).containsKey(SettingsField.MyXHandle)
            }
            assertThat(settings.current().myXHandle).isEmpty()
        }

    @Test
    fun `too many name characters are an error`() =
        runTest {
            val vm = viewModel()
            vm.editDraft { it.copy(myNames = "a".repeat(SettingsValidator.MAX_MY_NAMES + 1)) }
            vm.save()
            assertThat(vm.state.value.errors).containsKey(SettingsField.MyNames)
            assertThat(settings.current().myNames).isEmpty()
        }

    @Test
    fun `an invalid form is not saved and shows the errors`() =
        runTest {
            val vm = viewModel()
            vm.editDraft { it.copy(historyMessageCount = "0", retentionDays = "0") }
            vm.save()

            assertThat(settings.current()).isEqualTo(ReplylySettings(retentionDays = 60))
            assertThat(vm.state.value.errors.keys)
                .containsExactly(SettingsField.HistoryMessageCount, SettingsField.RetentionDays)
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
    fun `the dynamic colour switch is stored at once and keeps unsaved edits`() =
        runTest {
            val vm = viewModel()
            assertThat(vm.state.value.useDynamicColor).isFalse()
            vm.editDraft { it.copy(aboutMe = "typing...") }

            vm.setDynamicColor(true)

            assertThat(settings.current().useDynamicColor).isTrue()
            assertThat(vm.state.value.useDynamicColor).isTrue()
            assertThat(
                vm.state.value.draft
                    ?.aboutMe,
            ).isEqualTo("typing...")
            assertThat(vm.state.value.dirty).isTrue()

            vm.save()
            assertThat(settings.current().useDynamicColor).isTrue()
            vm.setDynamicColor(false)
            assertThat(settings.current().useDynamicColor).isFalse()
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
            val vm = SettingsViewModel(failing, cost, feedback, clock)

            vm.editDraft { it.copy(dailyRequestLimit = "50") }
            vm.save()
            assertThat(vm.state.value.saveError).isEqualTo("Could not save settings")
            assertThat(vm.state.value.savedCount).isEqualTo(0) // no confirm haptic for a failed write
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
            SettingsViewModel(settings, guard, feedback, clock).refreshUsage()
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

    /** A feedback log whose encrypted DB cannot be opened: the Keystore is locked, or the disk failed. */
    private class LockedFeedbackLog(
        delegate: FeedbackLog,
        private val failure: () -> Exception = { DatabaseKeyUnavailableException(IllegalStateException("locked")) },
    ) : FeedbackLog by delegate {
        var locked = true

        override fun observeRecent(limit: Int): Flow<List<FeedbackEntry>> =
            flow {
                if (locked) throw failure()
                emit(emptyList())
            }

        override suspend fun purgeOlderThan(cutoff: Instant): Int = if (locked) throw failure() else 0

        override suspend fun clear(): Int = if (locked) throw failure() else 0
    }

    @Test
    fun `a locked feedback DB on open shows the fixed message instead of crashing`() =
        runTest {
            val vm = SettingsViewModel(settings, cost, LockedFeedbackLog(feedback), clock)

            assertThat(vm.state.value.storageError).isEqualTo("Secure storage is locked. Try again.")
            assertThat(vm.state.value.storageError).isEqualTo(SettingsViewModel.STORAGE_LOCKED)
            vm.dismissStorageError()
            assertThat(vm.state.value.storageError).isNull()
        }

    @Test
    fun `a locked feedback DB shows an empty log with the fixed message`() =
        runTest {
            val vm = SettingsViewModel(settings, cost, LockedFeedbackLog(feedback), clock)
            vm.dismissStorageError()

            assertThat(vm.feedbackEntries.first()).isEmpty()
            assertThat(vm.state.value.storageError).isEqualTo(SettingsViewModel.STORAGE_LOCKED)
        }

    @Test
    fun `clearing a locked feedback log shows the fixed message, and works once unlocked`() =
        runTest {
            val locked = LockedFeedbackLog(feedback)
            val vm = SettingsViewModel(settings, cost, locked, clock)
            vm.dismissStorageError()

            vm.clearFeedbackLog()
            assertThat(vm.state.value.storageError).isEqualTo(SettingsViewModel.STORAGE_LOCKED)

            vm.dismissStorageError()
            locked.locked = false
            vm.clearFeedbackLog()
            assertThat(vm.state.value.storageError).isNull()
        }

    @Test
    fun `other storage failures show the same fixed message and never the exception text`() =
        runTest {
            val failures =
                listOf<() -> Exception>(
                    { IOException("disk I/O error at /data/secret/feedback.db") },
                    { IllegalStateException("file is not a database: chat text here") },
                    { RuntimeException(DatabaseKeyUnavailableException(IllegalStateException("wrapped"))) },
                )
            for (failure in failures) {
                val vm = SettingsViewModel(settings, cost, LockedFeedbackLog(feedback, failure), clock)
                assertThat(vm.state.value.storageError).isEqualTo(SettingsViewModel.STORAGE_LOCKED)
                vm.dismissStorageError()
                assertThat(vm.feedbackEntries.first()).isEmpty()
                assertThat(vm.state.value.storageError).isEqualTo(SettingsViewModel.STORAGE_LOCKED)
                assertThat(vm.state.value.toString()).doesNotContain("secret")
            }
        }
}
