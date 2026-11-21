package com.paragrudani.replyly.parser.whatsapp

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
import com.paragrudani.replyly.parser.generic.Sides
import com.paragrudani.replyly.parser.generic.TreeNode
import com.paragrudani.replyly.parser.generic.union
import com.paragrudani.replyly.screen.Bounds
import com.paragrudani.replyly.screen.ScreenSnapshot

/**
 * WhatsApp and WhatsApp Business (same app, same view ids under their own package).
 *
 * View ids first ([WhatsAppIds]): every `message_text`/`caption` is one message; its row gives the
 * time (`date`), the group sender label (`name_in_group_tv`) and the outgoing tick (`status`).
 * Quote previews, date dividers, system notices and banners have other ids, so they never count.
 * A label means incoming and a tick means mine; the other bubbles get their side from geometry
 * (right = me). If no message ids are found, the whole screen goes through [GeometryReader] instead.
 */
class WhatsAppParser : ExplainingChatParser {
    override val packages: Set<String> = setOf(TargetApps.WHATSAPP, TargetApps.WHATSAPP_BUSINESS)

    override fun explain(snapshot: ScreenSnapshot): ParseReport {
        val tree = ScreenTree(snapshot)
        val layout = ChatLayout.detect(tree)
        val idTitle = tree.byIdName(WhatsAppIds.TITLE).firstOrNull()?.text
        val idSubtitle = tree.byIdName(WhatsAppIds.SUBTITLE).firstOrNull()?.text
        val messageNodes = (WhatsAppIds.MESSAGE_TEXTS.flatMap(tree::byIdName)).sortedWith(ScreenTree.readingOrder)

        if (messageNodes.isNotEmpty() || idTitle != null) {
            val title = idTitle ?: layout.title ?: return ParseReport(null, METHOD_VIEW_IDS, listOf("no chat title"))
            val subtitle = if (idTitle != null) idSubtitle else layout.subtitle
            val area = layout.list?.bounds ?: tree.screen
            val bubbles = readByIds(messageNodes, area, layout.list)
            val isGroup = bubbles.any { it.label != null } || ChatText.groupHint(subtitle) == GroupHint.GROUP
            val chat = chat(snapshot, title, isGroup, bubbles)
            val notes =
                listOf(
                    "title from ${if (idTitle != null) "view id" else "geometry"}",
                    "${messageNodes.size} message ids, ${bubbles.count { it.text == null }} placeholders dropped",
                    "list ${if (layout.list != null) "found" else "not found"}",
                )
            return ParseReport(chat, METHOD_VIEW_IDS, notes)
        }

        // No WhatsApp ids at all: an app update renamed them, or this is not a chat.
        if (layout.input ==
            null
        ) {
            return ParseReport(null, METHOD_GEOMETRY, listOf("no ids and no input field: not a chat"))
        }
        val title = layout.title ?: return ParseReport(null, METHOD_GEOMETRY, listOf("no chat title"))
        val hint = ChatText.groupHint(layout.subtitle)
        val result = GeometryReader(GeometryReader.Mode.FULL).read(tree, layout, hint)
        val isGroup = hint == GroupHint.GROUP || result.labelsFound
        val notes =
            listOf(
                "no message ids: geometry fallback",
                "list ${if (layout.list != null) "found" else "not found"}",
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

    private class IdBubble(
        val text: String?,
        val time: String?,
        val label: String?,
        val tick: Boolean,
        val box: Bounds,
    )

    private fun readByIds(
        messageNodes: List<TreeNode>,
        area: Bounds,
        list: TreeNode?,
    ): List<RawBubble> {
        val raw =
            messageNodes.map { message ->
                val row = rowOf(message, messageNodes, list)
                val inRow = row.descendantsAndSelf().filter { it.shown }.toList()
                val time =
                    inRow
                        .filter {
                            it.idName == WhatsAppIds.TIME && it.text != null &&
                                it.bounds.top >= message.bounds.top - 4
                        }.minByOrNull { it.bounds.top }
                val label =
                    inRow
                        .firstOrNull {
                            it.idName == WhatsAppIds.SENDER_NAME &&
                                it.bounds.bottom <= message.bounds.top + 4
                        }?.text
                val tick = inRow.any { it.idName == WhatsAppIds.TICK }
                // The bubble is the widest container of the message that is still narrower than a row.
                val box =
                    (sequenceOf(message) + message.ancestors().takeWhile { it !== list && row.isAncestorOrSelf(it) })
                        .filter { it.bounds.width < area.width * GeometryReader.FULL_WIDTH }
                        .lastOrNull()
                        ?.bounds ?: listOfNotNull(message, time).map { it.bounds }.union()
                val text = message.text?.takeUnless(ChatText::isDeletedPlaceholder)
                IdBubble(text, time?.text, label, tick, box)
            }
        // A sender label only sits on incoming bubbles and a tick only on mine: those decide outright.
        val known =
            raw.map {
                when {
                    it.label != null -> Side.LEFT
                    it.tick -> Side.RIGHT
                    else -> null
                }
            }
        val sides = Sides.resolve(raw.map { it.box }, raw.map { it.tick }, area, known)
        return raw.mapIndexed { i, b -> RawBubble(sides[i], b.label, b.text, b.time) }
    }

    /** The largest ancestor of [message] (below the list) that holds no other message. */
    private fun rowOf(
        message: TreeNode,
        messages: List<TreeNode>,
        list: TreeNode?,
    ): TreeNode {
        var row = message
        while (true) {
            val parent = row.parent ?: return row
            if (parent === list) return row
            if (messages.any { it !== message && parent.isAncestorOf(it) }) return row
            row = parent
        }
    }

    private fun TreeNode.isAncestorOrSelf(other: TreeNode): Boolean = this === other || isAncestorOf(other)

    companion object {
        const val METHOD_VIEW_IDS = "whatsapp:view-ids"
        const val METHOD_GEOMETRY = "whatsapp:geometry"
    }
}

/**
 * WhatsApp view ids (entry names; the package part differs between WhatsApp and Business).
 * Public knowledge, unverified on the S23: see docs/device-checklist/ticket-2.md.
 */
object WhatsAppIds {
    const val TITLE = "conversation_contact_name"
    const val SUBTITLE = "conversation_contact_status"

    /** Message text, and the caption of a photo/video. */
    val MESSAGE_TEXTS = listOf("message_text", "caption")
    const val TIME = "date"
    const val SENDER_NAME = "name_in_group_tv"

    /** Read/delivered ticks: only on my own bubbles. */
    const val TICK = "status"
}
