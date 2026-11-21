package com.paragrudani.replyly.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.paragrudani.replyly.screen.Bounds

/**
 * Reads [ScreenFacts] with two IPC calls: the window list and the input-focused node. No tree
 * walk, so it is cheap enough to run on every relevant event. If the system call fails (the
 * service is going away), it reads as an empty screen, which hides the button.
 */
class ScreenFactsReader(
    private val windows: () -> List<AccessibilityWindowInfo>,
    private val focusedInput: () -> AccessibilityNodeInfo?,
    private val windowIdOf: (AccessibilityNodeInfo) -> Int = { it.windowId },
) {
    fun read(): ScreenFacts =
        try {
            ScreenFacts(windows().map(::toFacts), focusedInput()?.let(::toInput))
        } catch (e: RuntimeException) {
            ScreenFacts(emptyList(), null)
        }

    private fun toFacts(window: AccessibilityWindowInfo): WindowFacts {
        val kind =
            when (window.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> WindowKind.APPLICATION
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowKind.INPUT_METHOD
                else -> WindowKind.OTHER
            }
        val rect = Rect().also { window.getBoundsInScreen(it) }
        return WindowFacts(window.id, kind, rect.toBounds(), window.isActive, window.isFocused)
    }

    private fun toInput(node: AccessibilityNodeInfo): FocusedInput {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return FocusedInput(
            windowId = windowIdOf(node),
            packageName = node.packageName?.toString().orEmpty(),
            editable = node.isEditable,
            password = node.isPassword,
            viewId = node.viewIdResourceName,
            bounds = rect.toBounds(),
        )
    }

    private fun Rect.toBounds() = Bounds(left, top, right, bottom)

    companion object {
        fun of(service: AccessibilityService) =
            ScreenFactsReader(
                windows = { service.windows },
                focusedInput = { service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) },
            )
    }
}
