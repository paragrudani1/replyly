package com.paragrudani.replyly.insert

import com.paragrudani.replyly.screen.Bounds

/** A field that records what the inserter did to it. */
class FakeField(
    override val packageName: String = "com.whatsapp",
    override val viewId: String? = "com.whatsapp:id/entry",
    override val bounds: Bounds = Bounds(30, 2200, 900, 2320),
    override val password: Boolean = false,
    override val focused: Boolean = false,
    var acceptsText: Boolean = true,
    var acceptsSelection: Boolean = true,
) : EditableField {
    var text: String = "old draft"
    var selection: IntRange? = null
    val calls = mutableListOf<String>()

    override fun setText(text: String): Boolean {
        calls += "setText"
        if (acceptsText) this.text = text
        return acceptsText
    }

    override fun setSelection(
        start: Int,
        end: Int,
    ): Boolean {
        calls += "setSelection"
        if (acceptsSelection) selection = start..end
        return acceptsSelection
    }
}

/** Windows by id; a missing id is a window that is gone. */
class FakeFieldSource(
    val windows: MutableMap<Int, List<EditableField>> = mutableMapOf(),
) : InputFieldSource {
    override fun fields(
        windowId: Int,
        viewId: String?,
    ): List<EditableField>? = windows[windowId]
}
