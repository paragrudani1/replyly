package com.paragrudani.replyly.di

import android.app.Application
import com.paragrudani.replyly.cost.CostGuard
import com.paragrudani.replyly.cost.InMemoryCostGuard
import com.paragrudani.replyly.feedback.FeedbackLog
import com.paragrudani.replyly.feedback.InMemoryFeedbackLog
import com.paragrudani.replyly.history.ContactNoteStore
import com.paragrudani.replyly.history.HistoryRepository
import com.paragrudani.replyly.history.InMemoryContactNoteStore
import com.paragrudani.replyly.history.InMemoryHistoryRepository
import com.paragrudani.replyly.insert.ReplyInserter
import com.paragrudani.replyly.insert.UnavailableReplyInserter
import com.paragrudani.replyly.llm.ReplyGenerator
import com.paragrudani.replyly.llm.StubReplyGenerator
import com.paragrudani.replyly.llm.StubSuggestionRequestAssembler
import com.paragrudani.replyly.llm.SuggestionRequestAssembler
import com.paragrudani.replyly.panel.PlaceholderReplyPanel
import com.paragrudani.replyly.panel.ReplyPanel
import com.paragrudani.replyly.parser.ChatParserRegistry
import com.paragrudani.replyly.parser.Parsers
import com.paragrudani.replyly.policy.DefaultSensitiveScreenPolicy
import com.paragrudani.replyly.policy.SensitiveScreenPolicy
import com.paragrudani.replyly.security.DatabaseKeyProvider
import com.paragrudani.replyly.security.UnimplementedDatabaseKeyProvider
import com.paragrudani.replyly.service.AccessibilityComponent
import com.paragrudani.replyly.service.AccessibilityHostHolder
import com.paragrudani.replyly.service.NoOpAccessibilityComponent
import com.paragrudani.replyly.settings.ApiKeyStore
import com.paragrudani.replyly.settings.InMemoryApiKeyStore
import com.paragrudani.replyly.settings.InMemorySettingsRepository
import com.paragrudani.replyly.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock

/**
 * Manual DI: one binding per interface. A ticket replaces only its own bindings' right-hand side
 * (and adds its imports). Keep the comment line between bindings: it stops neighbouring edits
 * from becoming merge conflicts. Bindings are lazy, so they may use each other in any order.
 */
class AppGraph(
    val app: Application,
) {
    // Skeleton
    val clock: Clock = Clock.systemDefaultZone()

    // Skeleton: app-lifetime scope for work that must outlive a screen.
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Skeleton
    val accessibilityHostHolder: AccessibilityHostHolder = AccessibilityHostHolder()

    // Ticket #1: overlay and button.
    val overlayComponent: AccessibilityComponent by lazy { NoOpAccessibilityComponent }

    // Ticket #1
    val replyInserter: ReplyInserter by lazy { UnavailableReplyInserter }

    // Ticket #1
    val sensitiveScreenPolicy: SensitiveScreenPolicy by lazy {
        DefaultSensitiveScreenPolicy.create(settingsRepository, appScope)
    }

    // Ticket #2: register parsers in parser/Parsers.kt, not here.
    val chatParserRegistry: ChatParserRegistry by lazy { Parsers.registry() }

    // Ticket #3: passive capture.
    val captureComponent: AccessibilityComponent by lazy { NoOpAccessibilityComponent }

    // Ticket #3
    val databaseKeyProvider: DatabaseKeyProvider by lazy { UnimplementedDatabaseKeyProvider }

    // Ticket #3
    val historyRepository: HistoryRepository by lazy { InMemoryHistoryRepository(clock) }

    // Ticket #3
    val contactNoteStore: ContactNoteStore by lazy { InMemoryContactNoteStore() }

    // Ticket #4
    val replyGenerator: ReplyGenerator by lazy { StubReplyGenerator() }

    // Ticket #4
    val suggestionRequestAssembler: SuggestionRequestAssembler by lazy { StubSuggestionRequestAssembler() }

    // Ticket #5
    val replyPanel: ReplyPanel by lazy { PlaceholderReplyPanel }

    // Ticket #6
    val settingsRepository: SettingsRepository by lazy { InMemorySettingsRepository() }

    // Ticket #6
    val apiKeyStore: ApiKeyStore by lazy { InMemoryApiKeyStore() }

    // Ticket #6
    val costGuard: CostGuard by lazy { InMemoryCostGuard() }

    // Ticket #6: needs databaseKeyProvider, so keep the stub until #3 has merged.
    val feedbackLog: FeedbackLog by lazy { InMemoryFeedbackLog(clock) }

    // Skeleton: the order components receive events in.
    fun accessibilityComponents(): List<AccessibilityComponent> = listOf(overlayComponent, captureComponent)
}
