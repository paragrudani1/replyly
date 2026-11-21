package com.paragrudani.replyly.parser.generic

import com.paragrudani.replyly.screen.Bounds
import kotlin.math.abs

/**
 * Reads bubbles from screen geometry alone, with no view ids (spec 4, geometry rules).
 *
 * Each child of the message list is one unit (a row). Inside a unit:
 * - centred date separators, the encryption notice and unread banners are dropped;
 * - the bottom-right clock time is the bubble's time, never its text (also when fused with a label:
 *   "edited 6:42 pm" as the last leaf);
 * - "Edited", "Forwarded", tick words are dropped;
 * - the last remaining text is the message; in [Mode.FULL] the text above it is either a sender
 *   label (first line of an incoming group bubble) or a reply-quote preview (skipped);
 * - a centred unit without a time is a system message and is dropped.
 * The bubble box (the widest container of its text inside the row, narrower than the row) gives
 * the side ([Sides.resolve]).
 */
class GeometryReader(
    private val mode: Mode,
) {
    enum class Mode {
        /** WhatsApp/Telegram fallback: one message text per bubble, sender labels, quotes skipped. */
        FULL,

        /** Generic apps: all visible text of a bubble, no senders. */
        VISIBLE_TEXT,
    }

    class Result(
        val bubbles: List<RawBubble>,
        /** At least one sender label was found (so the chat is a group). */
        val labelsFound: Boolean,
        val units: Int,
        val skipped: Int,
    )

    private class Pending(
        val content: List<TreeNode>,
        var time: String?,
        val bubbleNode: TreeNode,
        val box: Bounds,
        val tick: Boolean,
    ) {
        var label: String? = null
        var side: Side = Side.LEFT
    }

    fun read(
        tree: ScreenTree,
        layout: ChatLayout,
        groupHint: GroupHint,
    ): Result {
        val area = layout.content
        val units = units(tree, layout)
        val pending = mutableListOf<Pending>()
        var skipped = 0
        for (unit in units) {
            val all = tree.textLeaves(unit).filter { it.bounds.overlapsVertically(area) }
            val leaves = all.filterNot { isCenteredChrome(it, area) }
            skipped += all.size - leaves.size
            if (leaves.isEmpty()) continue

            if (leaves.size == 1 && ChatText.timeOf(leaves[0].text!!) != null) {
                // A time on its own (flat lists): it belongs to the bubble right above it.
                val previous = pending.lastOrNull()
                if (previous != null && previous.time == null &&
                    leaves[0].bounds.top - previous.box.bottom <= ADJACENT
                ) {
                    previous.time = ChatText.timeOf(leaves[0].text!!)
                } else {
                    skipped++
                }
                continue
            }
            val timeLeaf = if (leaves.size >= 2) latestTime(leaves) else null
            val content = leaves.filter { it !== timeLeaf && !ChatText.isBubbleLabel(it.text!!) }
            if (content.isEmpty()) {
                skipped++
                continue
            }
            val main = content.last().text!!
            if (content.size == 1 && (ChatText.isEncryptionNotice(main) || ChatText.isUnreadBanner(main))) {
                skipped++
                continue
            }
            val textNodes = content + listOfNotNull(timeLeaf)
            val bubbleNode = ScreenTree.lowestCommonAncestor(textNodes)!!
            // The bubble is the widest container of its text, inside the row, still narrower than a
            // row: a lone text view sits inside the bubble's padding, which would hide its edge.
            val box =
                (sequenceOf(bubbleNode) + bubbleNode.ancestors().takeWhile { it === unit || unit.isAncestorOf(it) })
                    .filter { it.bounds.width < area.width * FULL_WIDTH }
                    .lastOrNull()
                    ?.bounds ?: textNodes.map { it.bounds }.union()
            if (timeLeaf == null && Sides.isCentered(box, area)) {
                // "Asha added Meera", "Missed voice call"...: system messages sit in the middle.
                skipped++
                continue
            }
            val tick =
                unit.descendantsAndSelf().any { n ->
                    n.shown && n.text == null && n.description?.let(ChatText::isTickDescription) == true
                }
            pending += Pending(content, timeLeaf?.text?.let(ChatText::timeOf), bubbleNode, box, tick)
        }

        Sides.resolve(pending.map { it.box }, pending.map { it.tick }, area).forEachIndexed { i, side ->
            pending[i].side = side
        }
        val labelsAllowed = mode == Mode.FULL && groupHint != GroupHint.ONE_TO_ONE
        if (labelsAllowed) {
            for (p in pending) p.label = insideLabel(p)
            if (groupHint == GroupHint.GROUP) skipped += mergeLabelRows(pending)
        }
        val bubbles = pending.mapNotNull(::toBubble)
        return Result(bubbles, labelsFound = pending.any { it.label != null }, units = units.size, skipped = skipped)
    }

    private fun toBubble(p: Pending): RawBubble? {
        val texts = p.content.map { it.text!! }
        return when (mode) {
            Mode.FULL -> {
                val text = texts.last().takeUnless(ChatText::isDeletedPlaceholder)
                RawBubble(p.side, p.label, text, p.time)
            }

            Mode.VISIBLE_TEXT -> {
                val visible = texts.filterNot(ChatText::isDeletedPlaceholder)
                if (visible.isEmpty()) null else RawBubble(p.side, null, visible.joinToString("\n"), p.time)
            }
        }
    }

    /**
     * Sender label inside an incoming bubble: its first line, above the message text, short, and
     * not part of a stacked block (a reply-quote preview holds a name above a text in its own box).
     */
    private fun insideLabel(p: Pending): String? {
        if (p.side != Side.LEFT || p.content.size < 2) return null
        val message = p.content.last()
        val others = p.content.dropLast(1)
        val candidate = others.first()
        val text = candidate.text!!
        if (text.length > MAX_LABEL || candidate.bounds.bottom > message.bounds.top + 4) return null
        val inStackedBlock =
            candidate.ancestors().takeWhile { it !== p.bubbleNode }.any { box ->
                others.any { o ->
                    o !== candidate && box.isAncestorOf(o) && abs(o.bounds.top - candidate.bounds.top) > SAME_LINE
                }
            }
        return text.takeUnless { inStackedBlock }
    }

    /**
     * A name on its own row right above an incoming bubble (apps that draw it outside the bubble).
     * Only for known groups, and only a short timeless single line. Returns how many rows it merged.
     */
    private fun mergeLabelRows(pending: MutableList<Pending>): Int {
        var merged = 0
        var i = 0
        while (i < pending.size - 1) {
            val row = pending[i]
            val next = pending[i + 1]
            val isLabelRow =
                row.side == Side.LEFT &&
                    row.content.size == 1 &&
                    row.time == null &&
                    row.content[0].text!!.length <= MAX_LABEL &&
                    next.side == Side.LEFT &&
                    next.label == null &&
                    next.box.top - row.box.bottom in 0..ADJACENT
            if (isLabelRow) {
                next.label = row.content[0].text
                pending.removeAt(i)
                merged++
            } else {
                i++
            }
        }
        return merged
    }

    private fun units(
        tree: ScreenTree,
        layout: ChatLayout,
    ): List<TreeNode> {
        val list = layout.list
        if (list == null) {
            val inputTop = layout.input?.bounds?.top ?: tree.screen.bottom
            return tree.textLeaves().filter {
                it.bounds.top >= layout.headerBottom && it.bounds.bottom <= inputTop + 4
            }
        }
        var units = list.children.filter { it.shown }
        // Some lists wrap all rows in one full-height container.
        while (units.size == 1 && units[0].children.isNotEmpty() &&
            units[0].bounds.height >= list.bounds.height * 0.9
        ) {
            units = units[0].children.filter { it.shown }
        }
        return units.filterNot { u -> u.descendantsAndSelf().any { it.editable } }.sortedWith(ScreenTree.readingOrder)
    }

    private fun isCenteredChrome(
        leaf: TreeNode,
        area: Bounds,
    ): Boolean {
        val text = leaf.text!!
        val chrome =
            ChatText.isDateSeparator(text) || ChatText.isEncryptionNotice(text) || ChatText.isUnreadBanner(text)
        return chrome && Sides.isCentered(leaf.bounds, area)
    }

    /**
     * The time-like text furthest down and right: the bubble's own time sits in its bottom-right
     * corner. The last leaf also counts when it is a label fused with the time ("edited 6:42 pm").
     */
    private fun latestTime(leaves: List<TreeNode>): TreeNode? =
        leaves
            .filter {
                ChatText.isTime(it.text!!) || (it === leaves.last() && ChatText.isLabelledTime(it.text))
            }.reduceOrNull {
                best,
                next,
                ->
                val sameLine = abs(next.bounds.bottom - best.bounds.bottom) <= SAME_LINE * 2
                val later =
                    if (sameLine) {
                        next.bounds.right >= best.bounds.right
                    } else {
                        next.bounds.bottom >
                            best.bounds.bottom
                    }
                if (later) next else best
            }

    companion object {
        /** A box this wide (share of the list) is a row, not a bubble. */
        const val FULL_WIDTH = 0.92

        /** Max vertical gap between parts of one bubble that the app split into separate rows. */
        const val ADJACENT = 40

        const val MAX_LABEL = 60
        const val SAME_LINE = 8
    }
}
