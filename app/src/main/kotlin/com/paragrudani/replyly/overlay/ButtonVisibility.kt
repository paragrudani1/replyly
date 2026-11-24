package com.paragrudani.replyly.overlay

import com.paragrudani.replyly.screen.Bounds

/** The kinds of window the button decision cares about (from `AccessibilityWindowInfo.type`). */
enum class WindowKind { APPLICATION, INPUT_METHOD, OTHER }

/** One on-screen window, as read from `AccessibilityService.getWindows()`. */
data class WindowFacts(
    val id: Int,
    val kind: WindowKind,
    val bounds: Bounds,
    val active: Boolean = false,
    val focused: Boolean = false,
)

/** The node that has input focus (`findFocus(FOCUS_INPUT)`), reduced to what the decision needs. */
data class FocusedInput(
    val windowId: Int,
    val packageName: String,
    val editable: Boolean,
    val password: Boolean,
    val viewId: String?,
    val bounds: Bounds,
    /** The field's hint ("Post your reply"), kept while the user types. `null` when it has none. */
    val hint: String? = null,
)

/** Everything the decision reads. Two cheap IPC calls fill it; no tree walk. */
data class ScreenFacts(
    val windows: List<WindowFacts>,
    val focusedInput: FocusedInput?,
)

sealed interface ButtonDecision {
    data class Show(
        val input: FocusedInput,
        val keyboard: Bounds,
    ) : ButtonDecision

    data class Hide(
        val reason: HideReason,
    ) : ButtonDecision
}

enum class HideReason {
    KEYBOARD_CLOSED,
    NO_INPUT_FOCUS,
    NOT_EDITABLE,
    PASSWORD_FIELD,

    /** The input has no visible area above the keyboard (a full-screen landscape keyboard). */
    INPUT_COVERED,
    WINDOW_NOT_ACTIVE,
    EXCLUDED_APP,
    BLOCKED_APP,

    /** An app-specific field that is not a reply: X search or a new post without a target (issue #30). */
    NOT_A_REPLY_FIELD,

    /** "Show bubble" is off in Settings (#55): hidden in every app; the panel and capture are unaffected. */
    TURNED_OFF,

    /** The bubble is snoozed (#56) until a time, a shake or "End snooze now"; checked after [TURNED_OFF]. */
    SNOOZED,
}

/**
 * Spec 3: the button shows only when an editable, non-password field of a target app has input
 * focus, its window is the active (or focused) app window, the keyboard is open, and the input
 * is not hidden behind the keyboard. Target app
 * = any app [isAppAllowed] lets through (WhatsApp, WhatsApp Business, Telegram, and the generic
 * mode for every other app), except Replyly itself and the system UI. [isFieldAllowed] narrows
 * an app to some of its fields: on X, the reply and quote composers and DMs only (`XFields`).
 * [isTurnedOff] ("Show bubble" off, #55) wins over every other reason; its default never hides.
 * A snooze (#56) comes next: hidden while [now] is before [snoozedUntil] (epoch ms,
 * [Long.MAX_VALUE] = until turned back on); comparing with the clock here means a late expiry
 * timer never keeps the button hidden. The default never hides.
 */
class ButtonVisibility(
    private val ownPackage: String,
    private val isAppAllowed: (packageName: String) -> Boolean,
    private val isFieldAllowed: (packageName: String, viewId: String?, hint: String?) -> Boolean = { _, _, _ -> true },
    private val isTurnedOff: () -> Boolean = { false },
    private val snoozedUntil: () -> Long? = { null },
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun decide(facts: ScreenFacts): ButtonDecision {
        if (isTurnedOff()) return hide(HideReason.TURNED_OFF)
        snoozedUntil()?.let { until -> if (now() < until) return hide(HideReason.SNOOZED) }
        val keyboard =
            facts.windows.firstOrNull { it.kind == WindowKind.INPUT_METHOD && it.bounds.height > 0 }
                ?: return hide(HideReason.KEYBOARD_CLOSED)
        val input = facts.focusedInput ?: return hide(HideReason.NO_INPUT_FOCUS)
        val window = facts.windows.firstOrNull { it.id == input.windowId }
        return when {
            window == null || window.kind != WindowKind.APPLICATION || !(window.active || window.focused) -> {
                hide(HideReason.WINDOW_NOT_ACTIVE)
            }

            input.packageName == ownPackage || input.packageName in EXCLUDED_PACKAGES -> {
                hide(HideReason.EXCLUDED_APP)
            }

            !input.editable -> {
                hide(HideReason.NOT_EDITABLE)
            }

            input.password -> {
                hide(HideReason.PASSWORD_FIELD)
            }

            input.bounds.width <= 0 || input.bounds.height <= 0 || keyboard.bounds.covers(input.bounds) -> {
                hide(HideReason.INPUT_COVERED)
            }

            !isFieldAllowed(input.packageName, input.viewId, input.hint) -> {
                hide(HideReason.NOT_A_REPLY_FIELD)
            }

            !isAppAllowed(input.packageName) -> {
                hide(HideReason.BLOCKED_APP)
            }

            else -> {
                ButtonDecision.Show(input, keyboard.bounds)
            }
        }
    }

    private fun hide(reason: HideReason) = ButtonDecision.Hide(reason)

    private fun Bounds.covers(other: Bounds) =
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

    private companion object {
        val EXCLUDED_PACKAGES = setOf("com.android.systemui")
    }
}
