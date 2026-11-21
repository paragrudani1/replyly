package com.paragrudani.replyly.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.paragrudani.replyly.appGraph
import com.paragrudani.replyly.screen.AccessibilityNodeMapper
import com.paragrudani.replyly.screen.ScreenSnapshot

/**
 * The only accessibility service. It holds no feature logic: it forwards every callback to the
 * [AccessibilityComponent]s from AppGraph. Add behaviour in a component, not here.
 */
class ReplylyAccessibilityService :
    AccessibilityService(),
    AccessibilityHost {
    private var components: List<AccessibilityComponent> = emptyList()

    override val service: AccessibilityService get() = this

    override fun onServiceConnected() {
        super.onServiceConnected()
        val graph = appGraph
        components = graph.accessibilityComponents()
        graph.accessibilityHostHolder.attach(this)
        dispatch { it.onConnected(this) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        dispatch { it.onEvent(event, this) }
    }

    override fun onInterrupt() {
        dispatch { it.onInterrupt() }
    }

    override fun onDestroy() {
        dispatch { it.onDisconnected() }
        components = emptyList()
        appGraph.accessibilityHostHolder.detach(this)
        super.onDestroy()
    }

    override fun captureActiveWindow(): ScreenSnapshot? {
        val root = rootInActiveWindow ?: return null
        val size = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        return AccessibilityNodeMapper.snapshot(root, size.width(), size.height(), appGraph.clock.instant())
    }

    private inline fun dispatch(call: (AccessibilityComponent) -> Unit) {
        for (component in components) {
            try {
                call(component)
            } catch (e: Exception) {
                // Class names only: exception messages can contain chat text.
                Log.e(TAG, "${component::class.simpleName} failed: ${e::class.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "ReplylyA11y"
    }
}
