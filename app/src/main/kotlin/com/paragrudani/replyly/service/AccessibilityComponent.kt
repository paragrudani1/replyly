package com.paragrudani.replyly.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.paragrudani.replyly.screen.ScreenSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A pluggable part of [ReplylyAccessibilityService]. The overlay (#1) and passive capture (#3)
 * each provide one, bound in AppGraph. Callbacks run on the main thread: return fast and move
 * work to a coroutine. An exception thrown here is caught and logged, and does not stop the
 * other components.
 */
interface AccessibilityComponent {
    fun onConnected(host: AccessibilityHost) {}

    /** [event] is recycled after this returns: copy what you need, never keep the event. */
    fun onEvent(
        event: AccessibilityEvent,
        host: AccessibilityHost,
    ) {}

    fun onInterrupt() {}

    fun onDisconnected() {}
}

object NoOpAccessibilityComponent : AccessibilityComponent

/** What a component can use from the running service. */
interface AccessibilityHost {
    /** For WindowManager (overlay windows), `windows` and `rootInActiveWindow`. */
    val service: AccessibilityService

    /** Maps the active window's tree to a snapshot, or `null` if there is no active window. */
    fun captureActiveWindow(): ScreenSnapshot?
}

/** Holds the running service, if any. Read by the debug dump (#2), the inserter (#1), onboarding (#7). */
class AccessibilityHostHolder {
    private val state = MutableStateFlow<AccessibilityHost?>(null)
    val host: StateFlow<AccessibilityHost?> = state

    internal fun attach(host: AccessibilityHost) {
        state.value = host
    }

    internal fun detach(host: AccessibilityHost) {
        state.compareAndSet(host, null)
    }
}
