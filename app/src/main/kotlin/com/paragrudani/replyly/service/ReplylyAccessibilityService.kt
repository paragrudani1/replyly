package com.paragrudani.replyly.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.paragrudani.replyly.appGraph
import com.paragrudani.replyly.policy.AccessibilityScreenshotProbe
import com.paragrudani.replyly.policy.SecureWindowDetector
import com.paragrudani.replyly.policy.SecureWindowState
import com.paragrudani.replyly.screen.AccessibilityNodeMapper
import com.paragrudani.replyly.screen.Bounds
import com.paragrudani.replyly.screen.ScreenNode
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

    override fun captureActiveWindow(): ScreenSnapshot? = rootInActiveWindow?.let { snapshotOf(it) }

    // Ticket #1: FLAG_SECURE probe (see SecureWindowDetector).
    private val secureWindows by lazy {
        SecureWindowDetector(
            AccessibilityScreenshotProbe(this),
            log = { Log.d(TAG, it) },
        )
    }

    override suspend fun captureActiveWindowChecked(): ScreenSnapshot? =
        windows.firstOrNull { it.isActive }?.let { checkedSnapshotOf(it) }

    override suspend fun captureWindowChecked(windowId: Int): ScreenSnapshot? =
        windows.firstOrNull { it.id == windowId }?.let { checkedSnapshotOf(it) }

    /**
     * Probes before fetching the root: fetching it pre-loads node text into this process. Fails
     * closed: anything but a certain "not secure" counts as secure. A gone window gives `null`.
     *
     * WrongConstant: getRoot's docs allow 0 ("either 0 or a combination of FLAG_PREFETCH_*"); lint's
     * IntDef does not.
     */
    @SuppressLint("WrongConstant")
    private suspend fun checkedSnapshotOf(window: AccessibilityWindowInfo): ScreenSnapshot? {
        val state = secureWindows.check(window.id)
        if (state == SecureWindowState.GONE) return null
        if (state != SecureWindowState.NOT_SECURE) {
            // The root node alone (no prefetching), for its package name only.
            return secureSnapshot(
                window
                    .getRoot(NO_PREFETCH)
                    ?.packageName
                    ?.toString()
                    .orEmpty(),
            )
        }
        return window.root?.let { snapshotOf(it) }
    }

    private fun snapshotOf(root: AccessibilityNodeInfo): ScreenSnapshot {
        val size = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        return AccessibilityNodeMapper.snapshot(root, size.width(), size.height(), appGraph.clock.instant())
    }

    /** A secure window is not read: its snapshot has an empty root. */
    private fun secureSnapshot(packageName: String): ScreenSnapshot {
        val size = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val now = appGraph.clock.instant()
        return ScreenSnapshot(
            packageName = packageName,
            capturedAt = now,
            screenWidth = size.width(),
            screenHeight = size.height(),
            root = ScreenNode(bounds = Bounds.EMPTY),
            secureWindow = true,
        )
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
        const val NO_PREFETCH = 0
    }
}
