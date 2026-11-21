package com.paragrudani.replyly.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.time.Instant

/** Maps a live `AccessibilityNodeInfo` tree to a [ScreenSnapshot]. Pure: it reads, never acts. */
object AccessibilityNodeMapper {
    /** Guards against pathological trees. Chat screens are far shallower. */
    const val MAX_DEPTH = 80

    fun snapshot(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        capturedAt: Instant,
    ): ScreenSnapshot =
        ScreenSnapshot(
            packageName = root.packageName?.toString().orEmpty(),
            capturedAt = capturedAt,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            root = map(root),
        )

    fun map(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
    ): ScreenNode {
        val rect = Rect().also(node::getBoundsInScreen)
        val children =
            if (depth >= MAX_DEPTH) {
                emptyList()
            } else {
                (0 until node.childCount).mapNotNull { index ->
                    node.getChild(index)?.let { map(it, depth + 1) }
                }
            }
        return ScreenNode(
            className = node.className?.toString(),
            viewId = node.viewIdResourceName,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            hint = node.hintText?.toString(),
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            editable = node.isEditable,
            focused = node.isFocused,
            password = node.isPassword,
            visible = node.isVisibleToUser,
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            children = children,
        )
    }
}
