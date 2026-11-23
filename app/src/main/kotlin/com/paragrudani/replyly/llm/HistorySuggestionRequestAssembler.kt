package com.paragrudani.replyly.llm

import android.content.pm.PackageManager
import com.paragrudani.replyly.history.ContactNoteStore
import com.paragrudani.replyly.history.HistoryRepository
import com.paragrudani.replyly.history.StoredMessage
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.parser.ParsedChat
import com.paragrudani.replyly.parser.TargetApps
import com.paragrudani.replyly.security.DatabaseKeyUnavailableException
import com.paragrudani.replyly.settings.SettingsRepository
import com.paragrudani.replyly.settings.current
import com.paragrudani.replyly.settings.myNameList
import com.paragrudani.replyly.settings.xPostLimit
import java.time.Clock
import java.time.ZoneId

/**
 * Builds the [SuggestionRequest] (spec 6). Merges the on-screen chat into history first, then
 * reads the last N messages, the contact note, About me and the style examples. Generic apps
 * have no history: they use the visible messages only. If the encrypted DB's key is unavailable
 * for a moment, it throws [SuggestionFailureException] with [SuggestionResult.Failure.StorageLocked].
 *
 * Message dates: a history message gets its stored date ([StoredMessage.date], approx when no date
 * separator was seen above it); an on-screen-only message (generic apps, or a chat whose history is
 * empty) gets the date its separator showed ([ChatMessage.date], exact, or none). `repliesToMe`
 * comes from the parsed message either way.
 *
 * X (issue #30): the request carries [ParsedChat.x], "My X voice" and "X post length"; the X prompt
 * reads the posts from there (my own posts on screen are its style examples), not from the messages.
 */
class HistorySuggestionRequestAssembler(
    private val history: HistoryRepository,
    private val notes: ContactNoteStore,
    private val settings: SettingsRepository,
    /** Label for apps outside [TargetApps]; see [packageManagerAppLabel]. */
    private val appLabelOf: (String) -> String = { it },
    private val clock: Clock = Clock.systemDefaultZone(),
    /** Read per request, so the "Now" header follows a time zone change without a restart. */
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** The phone's 12/24-hour setting (`DateFormat.is24HourFormat`), read per request. */
    private val is24Hour: () -> Boolean = { false },
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

        val zoneNow = zone()
        val messages: List<PromptMessage>
        val styleExamples: List<String>
        if (chat.historyApplies) {
            messages =
                history
                    .lastMessages(key, n)
                    .map { it.toPromptMessage() }
                    .ifEmpty { chat.messages.takeLast(n).map(::onScreen) }
            val mineHere =
                history
                    .lastMessages(key, STYLE_SCAN_MESSAGES)
                    .filter { it.message.isMe }
                    .takeLast(STYLE_FROM_THIS_CHAT)
            val mineElsewhere = history.recentOwnMessagesInOtherChats(chat.packageName, key, STYLE_FROM_OTHER_CHATS)
            styleExamples = (mineHere + mineElsewhere).map { it.message.text }
        } else {
            messages = chat.messages.takeLast(n).map(::onScreen)
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
            now = clock.instant().atZone(zoneNow),
            is24Hour = is24Hour(),
            myNames = current.myNameList(),
            x = chat.x,
            myXVoice = if (chat.x != null) current.myXVoice else "",
            xPostLimit = current.xPostLimit(),
        )
    }

    private fun StoredMessage.toPromptMessage() =
        PromptMessage(message = message, date = date, dateIsApprox = dateIsApprox, firstSeenAt = firstSeenAt)

    private fun onScreen(message: ChatMessage) = PromptMessage(message, date = message.date, dateIsApprox = false)

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
                TargetApps.X to "X",
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
