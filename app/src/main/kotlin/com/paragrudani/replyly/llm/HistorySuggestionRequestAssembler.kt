package com.paragrudani.replyly.llm

import android.content.pm.PackageManager
import com.paragrudani.replyly.history.ContactNoteStore
import com.paragrudani.replyly.history.HistoryRepository
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.parser.ParsedChat
import com.paragrudani.replyly.parser.TargetApps
import com.paragrudani.replyly.security.DatabaseKeyUnavailableException
import com.paragrudani.replyly.settings.SettingsRepository
import com.paragrudani.replyly.settings.current

/**
 * Builds the [SuggestionRequest] (spec 6). Merges the on-screen chat into history first, then
 * reads the last N messages, the contact note, About me and the style examples. Generic apps
 * have no history: they use the visible messages only. If the encrypted DB's key is unavailable
 * for a moment, it throws [SuggestionFailureException] with [SuggestionResult.Failure.StorageLocked].
 */
class HistorySuggestionRequestAssembler(
    private val history: HistoryRepository,
    private val notes: ContactNoteStore,
    private val settings: SettingsRepository,
    /** Label for apps outside [TargetApps]; see [packageManagerAppLabel]. */
    private val appLabelOf: (String) -> String = { it },
) : SuggestionRequestAssembler {
    override suspend fun assemble(
        chat: ParsedChat,
        instruction: String?,
    ): SuggestionRequest =
        try {
            build(chat, instruction)
        } catch (e: Exception) {
            if (e.causes().any { it is DatabaseKeyUnavailableException }) {
                throw SuggestionFailureException(SuggestionResult.Failure.StorageLocked)
            }
            throw e
        }

    private suspend fun build(
        chat: ParsedChat,
        instruction: String?,
    ): SuggestionRequest {
        history.merge(chat)
        val current = settings.current()
        val n = current.historyMessageCount.coerceAtLeast(1)
        val key = chat.chatKey

        val messages: List<ChatMessage>
        val styleExamples: List<String>
        if (chat.historyApplies) {
            messages = history.lastMessages(key, n).map { it.message }.ifEmpty { chat.messages.takeLast(n) }
            val mineHere =
                history
                    .lastMessages(key, STYLE_SCAN_MESSAGES)
                    .filter { it.message.isMe }
                    .takeLast(STYLE_FROM_THIS_CHAT)
            val mineElsewhere = history.recentOwnMessagesInOtherChats(chat.packageName, key, STYLE_FROM_OTHER_CHATS)
            styleExamples = (mineHere + mineElsewhere).map { it.message.text }
        } else {
            messages = chat.messages.takeLast(n)
            styleExamples = chat.messages.filter { it.isMe }.map { it.text }
        }

        return SuggestionRequest(
            packageName = chat.packageName,
            appLabel = KNOWN_LABELS[chat.packageName] ?: appLabelOf(chat.packageName),
            chatTitle = chat.title,
            isGroup = chat.isGroup,
            history = messages,
            aboutMe = current.aboutMe,
            contactNote = notes.get(key)?.takeIf { it.isNotBlank() },
            styleExamples = styleExamples,
            instruction = instruction?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    companion object {
        /** How far back to look for the user's own messages in this chat. */
        const val STYLE_SCAN_MESSAGES = 200

        /** At most 10, so other chats keep a 10-15 share of the 25 style examples. */
        const val STYLE_FROM_THIS_CHAT = 10
        const val STYLE_FROM_OTHER_CHATS = 15

        private val KNOWN_LABELS =
            mapOf(
                TargetApps.WHATSAPP to "WhatsApp",
                TargetApps.WHATSAPP_BUSINESS to "WhatsApp Business",
                TargetApps.TELEGRAM to "Telegram",
                // Common generic apps, in case package visibility hides their label.
                "com.Slack" to "Slack",
                "com.linkedin.android" to "LinkedIn",
                "com.instagram.android" to "Instagram",
                "com.facebook.orca" to "Messenger",
                "org.thoughtcrime.securesms" to "Signal",
                "com.google.android.apps.messaging" to "Messages",
                "com.google.android.gm" to "Gmail",
            )
    }
}

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH)

private const val MAX_CAUSE_DEPTH = 8

/** The installed app's launcher label, or the package name if it cannot be read. */
fun packageManagerAppLabel(packageManager: PackageManager): (String) -> String =
    { packageName ->
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }
