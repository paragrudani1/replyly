package com.paragrudani.replyly.ui.debug

import com.paragrudani.replyly.parser.ChatParserRegistry
import com.paragrudani.replyly.policy.BlockReason
import com.paragrudani.replyly.policy.ScreenContext
import com.paragrudani.replyly.policy.SensitiveScreenPolicy
import com.paragrudani.replyly.policy.SensitivityVerdict
import com.paragrudani.replyly.service.AccessibilityHost
import com.paragrudani.replyly.settings.ReplylySettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The delayed tree dump: Replyly cannot see WhatsApp while it is on top itself, so it counts down,
 * the user switches to the chat, then it reads whatever window is active through the accessibility
 * host and stores it. It only reads; it never acts on the other app. Screens the sensitive-screen
 * policy blocks (password fields, blocklisted chats...) are not stored, nor are apps or chats on the
 * settings blocklists.
 */
class DumpCapturer(
    private val hosts: StateFlow<AccessibilityHost?>,
    private val store: DebugDumpStore,
    private val policy: SensitiveScreenPolicy,
    private val registry: ChatParserRegistry,
    private val ownPackage: String,
    /**
     * Read before saving: its app and chat blocklists are applied here too, so a dump honours them
     * whatever the policy implementation does, and even when no chat could be parsed.
     */
    private val settings: suspend () -> ReplylySettings = { ReplylySettings() },
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    sealed interface Outcome {
        data object ServiceOff : Outcome

        data object NoWindow : Outcome

        /** The countdown ended with Replyly still on top. */
        data object StillOnReplyly : Outcome

        data class Blocked(
            val reason: BlockReason,
        ) : Outcome

        data class Saved(
            val entry: DebugDumpStore.Entry,
            /** False when the parser crashed or read no chat, so the chat blocklist could not be checked. */
            val chatKeyChecked: Boolean,
        ) : Outcome
    }

    /** Waits [after] (calling [onTick] with the whole seconds left), then captures. */
    suspend fun capture(
        after: Duration,
        onTick: (secondsLeft: Int) -> Unit = {},
    ): Outcome {
        if (hosts.value == null) return Outcome.ServiceOff
        var left = after.inWholeSeconds
        while (left > 0) {
            onTick(left.toInt())
            delay(1.seconds)
            left--
        }
        val host = hosts.value ?: return Outcome.ServiceOff
        val current = settings()
        return withContext(workDispatcher) {
            val snapshot = host.captureActiveWindow() ?: return@withContext Outcome.NoWindow
            if (snapshot.packageName == ownPackage) return@withContext Outcome.StillOnReplyly
            if (snapshot.packageName in current.blockedPackages) {
                return@withContext Outcome.Blocked(BlockReason.BLOCKLISTED_APP)
            }
            blocked(ScreenContext(snapshot.packageName, snapshot))?.let { return@withContext it }
            // A parser crash is exactly what a dump is for: keep the dump (the app checks above still
            // hold), and report that the chat blocklist could not be checked.
            val chat = runCatching { registry.parse(snapshot) }.getOrNull()
            if (chat != null) {
                if (chat.chatKey.encoded in current.blockedChatKeys) {
                    return@withContext Outcome.Blocked(BlockReason.BLOCKLISTED_CHAT)
                }
                blocked(ScreenContext(snapshot.packageName, snapshot, chat.chatKey))?.let { return@withContext it }
            }
            Outcome.Saved(store.save(snapshot), chatKeyChecked = chat != null)
        }
    }

    private fun blocked(context: ScreenContext): Outcome.Blocked? =
        (policy.evaluate(context) as? SensitivityVerdict.Blocked)?.let { Outcome.Blocked(it.reason) }
}
