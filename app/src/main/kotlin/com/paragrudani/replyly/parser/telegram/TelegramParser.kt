package com.paragrudani.replyly.parser.telegram

import com.paragrudani.replyly.parser.ExplainingChatParser
import com.paragrudani.replyly.parser.ParseReport
import com.paragrudani.replyly.parser.ParsedChat
import com.paragrudani.replyly.parser.TargetApps
import com.paragrudani.replyly.parser.generic.ChatAssembler
import com.paragrudani.replyly.parser.generic.ChatLayout
import com.paragrudani.replyly.parser.generic.ChatText
import com.paragrudani.replyly.parser.generic.GeometryReader
import com.paragrudani.replyly.parser.generic.GroupHint
import com.paragrudani.replyly.parser.generic.RawBubble
import com.paragrudani.replyly.parser.generic.ScreenTree
import com.paragrudani.replyly.parser.generic.Side
import com.paragrudani.replyly.screen.ScreenSnapshot

/**
 * Telegram draws each message as one custom full-width cell with no view ids, so cell bounds say
 * nothing about the side. The cell's accessibility description does (format unverified, see
 * docs/device-checklist/ticket-2.md):
 *
 * ```
 * [Sender name]            (groups, incoming only)
 * [Forwarded from X]
 * message text (one or more lines)
 * Sent today at 6:42 PM, Read      |  Received today at 6:42 PM
 * ```
 *
 * "Sent" = me. Cells without that last line (date headers, "Asha joined", "Unread messages") are not
 * messages. If no cell has it, the screen goes through [GeometryReader] instead.
 */
class TelegramParser : ExplainingChatParser {
    override val packages: Set<String> = setOf(TargetApps.TELEGRAM)

    override fun explain(snapshot: ScreenSnapshot): ParseReport {
        val tree = ScreenTree(snapshot)
        val layout = ChatLayout.detect(tree)
        val hint = ChatText.groupHint(layout.subtitle)
        val isGroupBySubtitle = hint == GroupHint.GROUP
        val cells =
            tree.shownNodes
                .filter { layout.list == null || layout.list.isAncestorOf(it) }
                .mapNotNull { node ->
                    CellDescription.parse(node.text, isGroupBySubtitle)
                        ?: CellDescription.parse(node.description, isGroupBySubtitle)
                }

        if (cells.isNotEmpty()) {
            val title = layout.title ?: return ParseReport(null, METHOD_DESCRIPTIONS, listOf("no chat title"))
            val bubbles = cells.map { RawBubble(if (it.isMe) Side.RIGHT else Side.LEFT, it.sender, it.text, it.time) }
            val notes =
                listOf(
                    "${cells.size} message cells",
                    "group from subtitle: ${hint.name.lowercase()}",
                )
            return ParseReport(chat(snapshot, title, isGroupBySubtitle, bubbles), METHOD_DESCRIPTIONS, notes)
        }

        if (layout.input ==
            null
        ) {
            return ParseReport(null, METHOD_GEOMETRY, listOf("no message cells and no input field"))
        }
        val title = layout.title ?: return ParseReport(null, METHOD_GEOMETRY, listOf("no chat title"))
        val result = GeometryReader(GeometryReader.Mode.FULL).read(tree, layout, hint)
        val isGroup = isGroupBySubtitle || result.labelsFound
        val notes =
            listOf(
                "no message cells: geometry fallback",
                "${result.units} rows, ${result.bubbles.size} bubbles, ${result.skipped} skipped",
            )
        return ParseReport(chat(snapshot, title, isGroup, result.bubbles), METHOD_GEOMETRY, notes)
    }

    private fun chat(
        snapshot: ScreenSnapshot,
        title: String,
        isGroup: Boolean,
        bubbles: List<RawBubble>,
    ) = ParsedChat(
        packageName = snapshot.packageName,
        title = title,
        isGroup = isGroup,
        messages = ChatAssembler.messages(bubbles, isGroup, oneToOneSender = title),
        historyApplies = true,
    )

    companion object {
        const val METHOD_DESCRIPTIONS = "telegram:cell-descriptions"
        const val METHOD_GEOMETRY = "telegram:geometry"
    }
}

/** One Telegram message cell, read from its accessibility text. */
internal data class CellDescription(
    val isMe: Boolean,
    val sender: String?,
    /** `null` when the cell has no text (a photo without caption). */
    val text: String?,
    val time: String?,
) {
    companion object {
        private val status = Regex("""^(sent|received)\b(.*)$""", RegexOption.IGNORE_CASE)
        private val forwarded = Regex("""^forwarded from\b.*""", RegexOption.IGNORE_CASE)

        /**
         * `null` if [raw] is not a message cell. The status line must be the last line and contain a
         * time, so a message that merely starts with "Sent ..." is not mistaken for one.
         */
        fun parse(
            raw: String?,
            isGroup: Boolean,
        ): CellDescription? {
            if (raw == null) return null
            val lines = raw.split('\n').map { it.trim() }.dropLastWhile { it.isEmpty() }
            if (lines.size < 2) return null
            val match = status.matchEntire(ChatText.squash(lines.last())) ?: return null
            val time = ChatText.findTime(lines.last()) ?: return null
            val isMe = match.groupValues[1].equals("sent", ignoreCase = true)
            var body = lines.dropLast(1)
            val sender = if (isGroup && !isMe && body.size >= 2) body.first() else null
            if (sender != null) body = body.drop(1)
            body = body.filterNot { forwarded.matches(ChatText.squash(it)) }
            val text = ChatText.clean(body.joinToString("\n"))?.takeUnless(ChatText::isDeletedPlaceholder)
            return CellDescription(isMe, ChatText.clean(sender), text, time)
        }
    }
}
