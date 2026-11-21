package com.paragrudani.replyly.insert

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.paragrudani.replyly.screen.Bounds

/**
 * [InputFieldSource] over the live accessibility windows. Runs only at insert time, so a bounded
 * tree walk is fine; with a view id it first tries the one-call `findAccessibilityNodeInfosByViewId`.
 */
class AccessibilityInputFieldSource(
    private val windows: () -> List<AccessibilityWindowInfo>,
) : InputFieldSource {
    override fun fields(
        windowId: Int,
        viewId: String?,
    ): List<EditableField>? {
        val root = windows().firstOrNull { it.id == windowId }?.root ?: return null
        val byId =
            viewId
                ?.let { id -> runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull() }
                .orEmpty()
                .filter { it.isEditable }
        val nodes = byId.ifEmpty { editableNodes(root) }
        return nodes.map(::NodeEditableField)
    }

    override fun appWindowIds(): List<Int> =
        windows().filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.map { it.id }

    private fun editableNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = stack.removeLast()
            visited++
            if (node.isEditable) found += node
            if (depth < MAX_DEPTH) {
                for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it to depth + 1) }
            }
        }
        return found
    }

    private companion object {
        const val MAX_DEPTH = 80
        const val MAX_NODES = 3000
    }
}

/** The only two actions Replyly performs on another app's node. */
private class NodeEditableField(
    private val node: AccessibilityNodeInfo,
) : EditableField {
    override val packageName: String = node.packageName?.toString().orEmpty()
    override val viewId: String? = node.viewIdResourceName
    override val bounds: Bounds =
        Rect()
            .also(
                node::getBoundsInScreen,
            ).let { Bounds(it.left, it.top, it.right, it.bottom) }
    override val password: Boolean = node.isPassword
    override val focused: Boolean = node.isFocused

    override fun setText(text: String): Boolean =
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
        )

    override fun setSelection(
        start: Int,
        end: Int,
    ): Boolean =
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            },
        )
}
