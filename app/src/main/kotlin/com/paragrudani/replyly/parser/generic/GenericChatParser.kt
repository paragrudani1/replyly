package com.paragrudani.replyly.parser.generic

import com.paragrudani.replyly.parser.ExplainingChatParser
import com.paragrudani.replyly.parser.ParseReport
import com.paragrudani.replyly.parser.ParsedChat
import com.paragrudani.replyly.screen.ScreenSnapshot

/**
 * Every app without a full parser (Slack, LinkedIn, Instagram...): the visible text only, no
 * history (spec 2). Bubbles still get a side from geometry (right = me), but no sender: a generic
 * app cannot tell a group from a 1:1 chat. A screen without a message input at the bottom is not a
 * chat (`null`).
 */
class GenericChatParser : ExplainingChatParser {
    override val packages: Set<String> = emptySet()

    override fun explain(snapshot: ScreenSnapshot): ParseReport {
        val tree = ScreenTree(snapshot)
        val layout = ChatLayout.detect(tree)
        if (layout.input == null) return ParseReport(null, METHOD, listOf("no input field: not a chat"))
        val result = GeometryReader(GeometryReader.Mode.VISIBLE_TEXT).read(tree, layout, GroupHint.UNKNOWN)
        val chat =
            ParsedChat(
                packageName = snapshot.packageName,
                title = layout.title.orEmpty(),
                isGroup = false,
                messages = ChatAssembler.messages(result.bubbles, isGroup = false, oneToOneSender = null),
                historyApplies = false,
            )
        val notes =
            listOf(
                "title ${if (layout.title != null) "found" else "not found"}",
                "list ${if (layout.list != null) "found" else "not found"}",
                "${result.units} rows, ${result.bubbles.size} bubbles, ${result.skipped} skipped",
            )
        return ParseReport(chat, METHOD, notes)
    }

    companion object {
        const val METHOD = "generic:visible-text"
    }
}
