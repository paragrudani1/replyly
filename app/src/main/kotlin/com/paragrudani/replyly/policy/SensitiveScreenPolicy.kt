package com.paragrudani.replyly.policy

import com.paragrudani.replyly.parser.ChatKey
import com.paragrudani.replyly.screen.ScreenSnapshot

/** What is known about the screen when deciding whether Replyly may read it. */
data class ScreenContext(
    val packageName: String,
    /** `null` for a cheap early check on the package alone, before reading the tree. */
    val snapshot: ScreenSnapshot? = null,
    /** Known once a parser has read the chat title. */
    val chatKey: ChatKey? = null,
    /** True if the window is known to have FLAG_SECURE. */
    val secureWindow: Boolean = false,
)

enum class BlockReason { BLOCKLISTED_APP, BLOCKLISTED_CHAT, PASSWORD_FIELD, BANKING_APP, SECURE_WINDOW }

sealed interface SensitivityVerdict {
    data object Allowed : SensitivityVerdict

    data class Blocked(
        val reason: BlockReason,
    ) : SensitivityVerdict
}

/**
 * Decides whether a screen may be read (spec 4). Both the overlay (#1) and passive capture (#3)
 * must ask before they parse, store or show anything. Ticket #1 implements it. Must be fast and
 * non-blocking: it runs on accessibility events.
 */
fun interface SensitiveScreenPolicy {
    fun evaluate(context: ScreenContext): SensitivityVerdict
}
