package com.paragrudani.replyly.parser.generic

import com.paragrudani.replyly.screen.Bounds
import kotlin.math.abs

/**
 * The parts of a chat screen found by geometry alone: the message input at the bottom, the
 * scrolling message list above it, and the toolbar above the list.
 */
class ChatLayout(
    /**
     * Bottom-most editable field below the top [INPUT_MIN_TOP] of the screen (an open keyboard
     * lifts the input to about the middle; search fields sit in the toolbar). `null` if none.
     */
    val input: TreeNode?,
    /** Largest scrollable node above the input. `null` if there is none. */
    val list: TreeNode?,
    /** Where messages are: the list, or the band between the toolbar and the input. */
    val content: Bounds,
    /**
     * Everything above this y is the toolbar: the list's top, or the top of its first row when the
     * list starts at the very top of the screen (a toolbar drawn over the list, as Telegram may do).
     */
    val headerBottom: Int,
    /**
     * Title candidates in reading order: text above [headerBottom], outside the list, minus pinned
     * message bars, unread badges and anything entirely left of the status line (back-button badges).
     */
    val headerTexts: List<TreeNode>,
) {
    /** First toolbar line that is not a status or a time. `null` when there is none. */
    val title: String? get() = titleNode?.text

    /** Toolbar line just below the title: "online", "Asha, Ravi, You", "5 members"... */
    val subtitle: String?
        get() {
            val title = titleNode ?: return null
            return headerTexts.firstOrNull { it !== title && it.bounds.top > title.bounds.top }?.text
        }

    private val titleNode: TreeNode?
        get() = headerTexts.firstOrNull { !ChatText.isStatusLine(it.text!!) && !ChatText.isTime(it.text) }

    companion object {
        /** Toolbar height used when there is no list (or no row) to mark where the toolbar ends. */
        private const val HEADER_FRACTION = 0.13

        /** A list whose top is above this share of the screen starts behind the toolbar. */
        private const val LIST_BEHIND_TOOLBAR = 0.05

        fun detect(tree: ScreenTree): ChatLayout {
            val screen = tree.screen
            val input =
                tree.shownNodes
                    .filter { it.editable && it.bounds.hasArea && it.bounds.top >= screen.height * INPUT_MIN_TOP }
                    .maxByOrNull { it.bounds.top }
            val inputTop = input?.bounds?.top ?: screen.bottom
            val list =
                tree.shownNodes
                    .filter {
                        it.node.scrollable &&
                            !it.editable &&
                            it.bounds.bottom <= inputTop + SLACK &&
                            it.bounds.height >= screen.height / 5
                    }.maxWithOrNull(compareBy<TreeNode>({ it.bounds.width.toLong() * it.bounds.height }, { it.depth }))
            val fallbackHeader = (screen.height * HEADER_FRACTION).toInt()
            val headerBottom =
                when {
                    list == null -> {
                        fallbackHeader
                    }

                    list.bounds.top >= screen.height * LIST_BEHIND_TOOLBAR -> {
                        list.bounds.top
                    }

                    else -> {
                        list.children.filter { it.shown && it.bounds.hasArea }.minOfOrNull { it.bounds.top }
                            ?: fallbackHeader
                    }
                }
            val content = list?.bounds ?: Bounds(screen.left, headerBottom, screen.right, inputTop)
            val above =
                tree
                    .textLeaves()
                    .filter { it.bounds.bottom <= headerBottom + SLACK && (list == null || !list.isAncestorOf(it)) }
            return ChatLayout(input, list, content, headerBottom, titleCandidates(tree, above))
        }

        private fun titleCandidates(
            tree: ScreenTree,
            above: List<TreeNode>,
        ): List<TreeNode> {
            // A pinned bar ("Pinned message" + its text) holds message text: drop the whole bar, that
            // is the label's parent, unless the parent also holds lines above it (the toolbar itself).
            val pinnedBars =
                above
                    .filter { ChatText.isPinnedLabel(it.text!!) }
                    .map { label ->
                        label.parent?.takeIf { bar ->
                            bar !== tree.root &&
                                above.none { bar.isAncestorOf(it) && it.bounds.bottom <= label.bounds.top }
                        } ?: label
                    }
            val status = above.firstOrNull { ChatText.isStatusLine(it.text!!) }
            return above.filterNot { leaf ->
                pinnedBars.any { it === leaf || it.isAncestorOf(leaf) } ||
                    ChatText.isBadge(leaf.text!!) ||
                    (status != null && leaf !== status && leaf.bounds.right <= status.bounds.left)
            }
        }

        private const val SLACK = 16

        /** Share of the screen height above which an editable field is a search box, not the message input. */
        const val INPUT_MIN_TOP = 0.3
    }
}

/** Which side of the chat a bubble is on. Right = me (spec 4). */
enum class Side { LEFT, RIGHT }

/** The geometry rules for bubble sides, shared by every parser. */
object Sides {
    /** Gap difference below which a box counts as centred. */
    fun tolerance(area: Bounds): Int = maxOf(16, area.width / 25)

    private fun gaps(
        box: Bounds,
        area: Bounds,
    ): Pair<Int, Int> = (box.left - area.left) to (area.right - box.right)

    /** Same distance from both edges of [area] (date separators, system notices, full-width banners). */
    fun isCentered(
        box: Bounds,
        area: Bounds,
    ): Boolean {
        val (left, right) = gaps(box, area)
        return abs(left - right) <= tolerance(area)
    }

    /**
     * Largest right gap of a bubble that counts as "nearer the right": mine hug the right edge, while
     * a wide incoming bubble pushed right by a group's avatar column stops short of it.
     */
    fun maxRightGap(area: Bounds): Int = area.width * RIGHT_GAP_PERCENT / 100

    /** Nearer the right (-> +1), the left (-> -1), or centred (0), for picking the anchors. */
    private fun nearer(
        box: Bounds,
        area: Bounds,
    ): Int {
        if (isCentered(box, area)) return 0
        val (left, right) = gaps(box, area)
        return if (right <= maxRightGap(area) && right < left) 1 else -1
    }

    /**
     * Decides the side of each bubble (right = me). [known] holds sides already proven by the app
     * (a sender label means left, an outgoing tick means right); those win, and the rest go by
     * geometry. Incoming bubbles share one left edge and mine share one right edge, so the edges
     * decide first:
     * 1. a bubble is nearer the right only when its right gap is at most [maxRightGap]; otherwise it
     *    is nearer the left (or centred). The left anchor is the leftmost left edge of the bubbles
     *    known or nearer to be left; the right anchor is the rightmost right edge of the bubbles
     *    known or nearer to be right;
     * 2. a bubble on exactly one anchor is on that side (a wide incoming group bubble, pushed right
     *    by the avatar column, still starts at the left anchor), unless it is nearly centred and
     *    ticks are on screen;
     * 3. if any bubble on screen has an outgoing tick, a tick means me and no tick means not me;
     * 4. otherwise right only when nearer the right, else left.
     */
    fun resolve(
        bubbles: List<Bounds>,
        tickHints: List<Boolean>,
        area: Bounds,
        known: List<Side?> = emptyList(),
    ): List<Side> {
        val fixed = bubbles.indices.map { known.getOrNull(it) }
        val nearer =
            bubbles.mapIndexed { i, box ->
                when (fixed[i]) {
                    Side.LEFT -> -1
                    Side.RIGHT -> 1
                    null -> nearer(box, area)
                }
            }
        val leftAnchor = bubbles.filterIndexed { i, _ -> nearer[i] < 0 }.minOfOrNull { it.left }
        val rightAnchor = bubbles.filterIndexed { i, _ -> nearer[i] > 0 }.maxOfOrNull { it.right }
        val ticksOnScreen = tickHints.any { it }
        return bubbles.mapIndexed { i, box ->
            val onLeft = leftAnchor != null && abs(box.left - leftAnchor) <= EDGE_MATCH
            val onRight = rightAnchor != null && abs(box.right - rightAnchor) <= EDGE_MATCH
            val anchorDecides = onLeft != onRight && (!ticksOnScreen || !isCentered(box, area))
            fixed[i] ?: when {
                anchorDecides -> if (onLeft) Side.LEFT else Side.RIGHT
                ticksOnScreen -> if (tickHints[i]) Side.RIGHT else Side.LEFT
                nearer[i] > 0 -> Side.RIGHT
                else -> Side.LEFT
            }
        }
    }

    /** See [maxRightGap]. Unverified on the S23: device checklist. */
    private const val RIGHT_GAP_PERCENT = 5

    private const val EDGE_MATCH = 12
}
