package com.paragrudani.replyly.overlay

import com.paragrudani.replyly.parser.ChatParserRegistry
import com.paragrudani.replyly.parser.ParsedChat
import com.paragrudani.replyly.policy.BlockReason
import com.paragrudani.replyly.policy.ScreenContext
import com.paragrudani.replyly.policy.SensitiveScreenPolicy
import com.paragrudani.replyly.policy.SensitivityVerdict
import com.paragrudani.replyly.screen.ScreenSnapshot

sealed interface TapOutcome {
    data class Open(
        val chat: ParsedChat,
    ) : TapOutcome

    data class Blocked(
        val reason: BlockReason,
    ) : TapOutcome

    /** No active window, another app took over, or the parser found no chat. */
    data object NotAChat : TapOutcome
}

/**
 * What a tap on the button does before the panel opens: ask the policy about the app (before
 * reading anything), capture the active window (checked for FLAG_SECURE), ask the policy about
 * the screen, parse, and ask the policy about the chat (a blocklisted chat is known only now).
 */
class TapFlow(
    private val capture: suspend () -> ScreenSnapshot?,
    private val policy: SensitiveScreenPolicy,
    private val parsers: ChatParserRegistry,
) {
    suspend fun run(packageName: String): TapOutcome {
        blocked(ScreenContext(packageName))?.let { return it }
        val snapshot = capture()?.takeIf { it.packageName == packageName } ?: return TapOutcome.NotAChat
        blocked(ScreenContext(packageName, snapshot, secureWindow = snapshot.secureWindow))?.let { return it }
        val chat = parsers.parse(snapshot) ?: return TapOutcome.NotAChat
        blocked(ScreenContext(packageName, snapshot, chat.chatKey, snapshot.secureWindow))?.let { return it }
        return TapOutcome.Open(chat)
    }

    private fun blocked(context: ScreenContext): TapOutcome.Blocked? =
        (policy.evaluate(context) as? SensitivityVerdict.Blocked)?.let { TapOutcome.Blocked(it.reason) }
}
