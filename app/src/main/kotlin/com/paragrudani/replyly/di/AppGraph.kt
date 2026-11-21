package com.paragrudani.replyly.di

import android.app.Application
import com.paragrudani.replyly.cost.CostGuard
import com.paragrudani.replyly.cost.PersistentCostGuard
import com.paragrudani.replyly.feedback.FeedbackLog
import com.paragrudani.replyly.feedback.RoomFeedbackLog
import com.paragrudani.replyly.feedback.db.FeedbackDatabase
import com.paragrudani.replyly.history.ContactNoteStore
import com.paragrudani.replyly.history.HistoryRepository
import com.paragrudani.replyly.history.RoomContactNoteStore
import com.paragrudani.replyly.history.RoomHistoryRepository
import com.paragrudani.replyly.history.capture.PassiveCaptureComponent
import com.paragrudani.replyly.history.db.HistoryDatabases
import com.paragrudani.replyly.insert.AccessibilityReplyInserter
import com.paragrudani.replyly.insert.ReplyInserter
import com.paragrudani.replyly.llm.HistorySuggestionRequestAssembler
import com.paragrudani.replyly.llm.OpenAiReplyGenerator
import com.paragrudani.replyly.llm.ReplyGenerator
import com.paragrudani.replyly.llm.SuggestionRequestAssembler
import com.paragrudani.replyly.llm.packageManagerAppLabel
import com.paragrudani.replyly.overlay.OverlayComponent
import com.paragrudani.replyly.panel.ComposeReplyPanel
import com.paragrudani.replyly.panel.ReplyPanel
import com.paragrudani.replyly.panel.ReplyPanelDependencies
import com.paragrudani.replyly.parser.ChatParserRegistry
import com.paragrudani.replyly.parser.Parsers
import com.paragrudani.replyly.policy.DefaultSensitiveScreenPolicy
import com.paragrudani.replyly.policy.SensitiveScreenPolicy
import com.paragrudani.replyly.policy.SettingsUnavailableNotice
import com.paragrudani.replyly.security.DatabaseKeyProvider
import com.paragrudani.replyly.security.KeystoreDatabaseKeyProvider
import com.paragrudani.replyly.service.AccessibilityComponent
import com.paragrudani.replyly.service.AccessibilityHostHolder
import com.paragrudani.replyly.settings.AesGcmCipher
import com.paragrudani.replyly.settings.AndroidKeystoreKeys
import com.paragrudani.replyly.settings.ApiKeyStore
import com.paragrudani.replyly.settings.DataStoreSettingsRepository
import com.paragrudani.replyly.settings.EncryptedApiKeyStore
import com.paragrudani.replyly.settings.ReplylyDataStores
import com.paragrudani.replyly.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import java.time.Clock

/**
 * Manual DI: one binding per interface. A ticket replaces only its own bindings' right-hand side
 * (and adds its imports). Keep the comment line between bindings: it stops neighbouring edits
 * from becoming merge conflicts. Bindings are lazy, so they may use each other in any order.
 */
class AppGraph(
    val app: Application,
) {
    // Skeleton. The zone is fixed at process start: a time zone change applies after the app restarts.
    val clock: Clock = Clock.systemDefaultZone()

    // Skeleton: app-lifetime scope for work that must outlive a screen.
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Skeleton
    val accessibilityHostHolder: AccessibilityHostHolder = AccessibilityHostHolder()

    // Ticket #1: overlay and button.
    val overlayComponent: AccessibilityComponent by lazy { OverlayComponent.create(this) }

    // Ticket #1
    val replyInserter: ReplyInserter by lazy { accessibilityReplyInserter }

    // Ticket #1: the same inserter, typed, so the overlay can remember the insert target.
    val accessibilityReplyInserter: AccessibilityReplyInserter by lazy {
        AccessibilityReplyInserter.create(accessibilityHostHolder)
    }

    // Ticket #1
    val sensitiveScreenPolicy: SensitiveScreenPolicy by lazy {
        DefaultSensitiveScreenPolicy.create(settingsRepository, appScope, onUnavailable = {
            SettingsUnavailableNotice.show(app)
        })
    }

    // Ticket #2: register parsers in parser/Parsers.kt, not here.
    val chatParserRegistry: ChatParserRegistry by lazy { Parsers.registry() }

    // Ticket #3: passive capture.
    val captureComponent: AccessibilityComponent by lazy { PassiveCaptureComponent.create(this) }

    // Ticket #3
    val databaseKeyProvider: DatabaseKeyProvider by lazy { KeystoreDatabaseKeyProvider.create(app) }

    // Ticket #3
    val historyRepository: HistoryRepository by lazy {
        RoomHistoryRepository(HistoryDatabases.shared(app, databaseKeyProvider), clock)
    }

    // Ticket #3
    val contactNoteStore: ContactNoteStore by lazy {
        RoomContactNoteStore(HistoryDatabases.shared(app, databaseKeyProvider), clock)
    }

    // Ticket #4
    val replyGenerator: ReplyGenerator by lazy { OpenAiReplyGenerator(settingsRepository, apiKeyStore, costGuard) }

    // Ticket #4
    val suggestionRequestAssembler: SuggestionRequestAssembler by lazy {
        HistorySuggestionRequestAssembler(
            historyRepository,
            contactNoteStore,
            settingsRepository,
            packageManagerAppLabel(app.packageManager),
        )
    }

    // Ticket #5
    val replyPanel: ReplyPanel by lazy {
        ComposeReplyPanel(
            ReplyPanelDependencies(
                suggestionRequestAssembler,
                replyGenerator,
                replyInserter,
                feedbackLog,
                contactNoteStore,
                historyRepository,
            ),
        )
    }

    // Ticket #6
    val settingsRepository: SettingsRepository by lazy {
        DataStoreSettingsRepository(ReplylyDataStores.create(app, ReplylyDataStores.SETTINGS))
    }

    // Ticket #6
    val apiKeyStore: ApiKeyStore by lazy {
        EncryptedApiKeyStore(
            ReplylyDataStores.create(app, ReplylyDataStores.API_KEY),
            AesGcmCipher { AndroidKeystoreKeys.getOrCreateAesKey(AndroidKeystoreKeys.API_KEY_ALIAS) },
        )
    }

    // Ticket #6
    val costGuard: CostGuard by lazy {
        PersistentCostGuard(
            ReplylyDataStores.create(app, ReplylyDataStores.COST),
            settingsRepository.settings.map { it.dailyRequestLimit },
            clock,
            appScope,
        )
    }

    // Ticket #6: opens the encrypted feedback.db on first use, off the main thread.
    val feedbackLog: FeedbackLog by lazy {
        RoomFeedbackLog(
            { FeedbackDatabase.open(app, databaseKeyProvider) },
            clock,
            settingsRepository.settings.map { it.retentionDays },
        )
    }

    // Skeleton: the order components receive events in.
    fun accessibilityComponents(): List<AccessibilityComponent> = listOf(overlayComponent, captureComponent)
}
