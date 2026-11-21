package com.paragrudani.replyly.screen

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * One read of the screen tree of another app.
 *
 * The same JSON is the debug-dump format (debug screen, ticket #2) and the parser fixture format
 * (`app/src/test/resources/fixtures/<app>/`). Keep it stable: add fields only with defaults, never
 * rename or remove one, and bump [formatVersion] if the meaning of an existing field changes.
 * Encode and decode only through [ScreenSnapshotJson].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ScreenSnapshot(
    // Always written, so every dump says which format it is.
    @EncodeDefault
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val packageName: String,
    @Serializable(with = InstantIsoSerializer::class)
    val capturedAt: Instant,
    val screenWidth: Int,
    val screenHeight: Int,
    val root: ScreenNode,
) {
    /** All nodes, depth first, root included. */
    fun allNodes(): Sequence<ScreenNode> = root.descendantsAndSelf()

    companion object {
        const val CURRENT_FORMAT_VERSION = 1
    }
}

/**
 * One node of the tree, mapped from an `AccessibilityNodeInfo` by [AccessibilityNodeMapper].
 * Fields with default values are left out of the JSON to keep fixtures short.
 */
@Serializable
data class ScreenNode(
    val className: String? = null,
    /** Full resource name, for example `com.whatsapp:id/message_text`. */
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val hint: String? = null,
    val bounds: Bounds,
    val editable: Boolean = false,
    val focused: Boolean = false,
    val password: Boolean = false,
    val visible: Boolean = true,
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    val children: List<ScreenNode> = emptyList(),
) {
    fun descendantsAndSelf(): Sequence<ScreenNode> =
        sequence {
            yield(this@ScreenNode)
            children.forEach { yieldAll(it.descendantsAndSelf()) }
        }
}

/** Screen coordinates in pixels. Serialized as `"[left,top][right,bottom]"`, like `uiautomator dump`. */
@Serializable(with = BoundsSerializer::class)
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    companion object {
        val EMPTY = Bounds(0, 0, 0, 0)
    }
}
