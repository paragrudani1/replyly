package com.paragrudani.replyly.ui.debug

import com.paragrudani.replyly.parser.ChatParserRegistry
import com.paragrudani.replyly.policy.BlockReason
import com.paragrudani.replyly.policy.ScreenContext
import com.paragrudani.replyly.policy.SensitiveScreenPolicy
import com.paragrudani.replyly.policy.SensitivityVerdict
import com.paragrudani.replyly.service.AccessibilityHost
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
 * policy blocks (password fields, blocklisted chats...) are not stored.
 */
class DumpCapturer(
    private val hosts: StateFlow<AccessibilityHost?>,
    private val store: DebugDumpStore,
    private val policy: SensitiveScreenPolicy,
    private val registry: ChatParserRegistry,
    private val ownPackage: String,
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
        return withContext(workDispatcher) {
            val snapshot = host.captureActiveWindow() ?: return@withContext Outcome.NoWindow
            if (snapshot.packageName == ownPackage) return@withContext Outcome.StillOnReplyly
            blocked(ScreenContext(snapshot.packageName, snapshot))?.let { return@withContext it }
            // A parser crash is exactly what a dump is for: keep the dump, skip the chat check.
            val chat = runCatching { registry.parse(snapshot) }.getOrNull()
            if (chat != null) {
                blocked(ScreenContext(snapshot.packageName, snapshot, chat.chatKey))?.let { return@withContext it }
            }
            Outcome.Saved(store.save(snapshot))
        }
    }

    private fun blocked(context: ScreenContext): Outcome.Blocked? =
        (policy.evaluate(context) as? SensitivityVerdict.Blocked)?.let { Outcome.Blocked(it.reason) }
}
