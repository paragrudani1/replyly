package com.paragrudani.replyly.insert

import com.paragrudani.replyly.screen.Bounds

/**
 * A re-findable reference to the chat's input field, taken when the user taps the button.
 * A live `AccessibilityNodeInfo` goes stale (the panel takes focus, the keyboard closes and the
 * input bar moves), so the inserter finds the field again from this at insert time.
 */
data class InputFieldRef(
    val windowId: Int,
    val packageName: String,
    /** Full resource name, `null` if the app gives none (then bounds and focus decide). */
    val viewId: String?,
    /** Where the field was at tap time. */
    val bounds: Bounds,
)

/**
 * A live editable field of another app. It can only set text and move the cursor: Replyly
 * never clicks, presses Enter or sends (AGENTS.md invariant 1).
 */
interface EditableField {
    val packageName: String
    val viewId: String?
    val bounds: Bounds
    val password: Boolean
    val focused: Boolean

    /** Replaces the whole text. False if the app refused. */
    fun setText(text: String): Boolean

    fun setSelection(
        start: Int,
        end: Int,
    ): Boolean
}

/** Reads editable fields from the live windows. */
interface InputFieldSource {
    /** Editable fields of window [windowId], or `null` if that window is gone. [viewId] is a hint. */
    fun fields(
        windowId: Int,
        viewId: String?,
    ): List<EditableField>?
}

/**
 * Finds the field an [InputFieldRef] points at, only in the remembered window: if that window is
 * gone, there is no field (another window of the same app may hold another chat). Only fields of
 * the same app, never a password field. Same view id wins; with no view id: the only field, else a focused one, else the one
 * nearest the remembered bounds.
 */
object InputFieldFinder {
    fun find(
        ref: InputFieldRef,
        source: InputFieldSource,
    ): EditableField? = source.fields(ref.windowId, ref.viewId)?.let { pick(ref, it) }

    private fun pick(
        ref: InputFieldRef,
        fields: List<EditableField>,
    ): EditableField? {
        val candidates = fields.filter { it.packageName == ref.packageName && !it.password }
        if (candidates.isEmpty()) return null
        val sameId = if (ref.viewId != null) candidates.filter { it.viewId == ref.viewId } else emptyList()
        val pool =
            when {
                sameId.isNotEmpty() -> sameId
                candidates.size == 1 -> candidates
                candidates.any { it.focused } -> candidates.filter { it.focused }
                else -> candidates
            }
        return pool.minBy { distance(it.bounds, ref.bounds) }
    }

    private fun distance(
        a: Bounds,
        b: Bounds,
    ): Long {
        val dx = (a.centerX - b.centerX).toLong()
        val dy = (a.centerY - b.centerY).toLong()
        return dx * dx + dy * dy
    }
}
