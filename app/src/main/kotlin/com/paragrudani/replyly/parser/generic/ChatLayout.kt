package com.paragrudani.replyly.parser.generic

import com.paragrudani.replyly.screen.Bounds
import kotlin.math.abs

/**
 * The parts of a chat screen found by geometry alone: the message input at the bottom, the
 * scrolling message list above it, and the toolbar above the list.
 */
class ChatLayout(
    /** Bottom-most editable field in the lower half of the screen. `null` if there is none. */
    val input: TreeNode?,
    /** Largest scrollable node above the input. `null` if there is none. */
    val list: TreeNode?,
    /** Where messages are: the list, or the band between the toolbar and the input. */
    val content: Bounds,
    /** Everything above this y is the toolbar. */
    val headerBottom: Int,
    /** Toolbar text lines in reading order (title first, then subtitle). */
    val headerTexts: List<TreeNode>,
) {
    /** First toolbar line that is not a status or a time. */
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
        /** Toolbar height used when there is no list to mark where the toolbar ends. */
        private const val HEADER_FRACTION = 0.13

        fun detect(tree: ScreenTree): ChatLayout {
            val screen = tree.screen
            val input =
                tree.shownNodes
                    .filter { it.editable && it.bounds.hasArea && it.bounds.top >= screen.height / 2 }
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
            val headerBottom = list?.bounds?.top ?: (screen.height * HEADER_FRACTION).toInt()
            val content = list?.bounds ?: Bounds(screen.left, headerBottom, screen.right, inputTop)
            val headerTexts =
                tree
                    .textLeaves()
                    .filter { it.bounds.bottom <= headerBottom + SLACK && (list == null || !list.isAncestorOf(it)) }
            return ChatLayout(input, list, content, headerBottom, headerTexts)
        }

        private const val SLACK = 16
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
     * Decides the side of each bubble (right = me). Incoming bubbles share one left edge and mine
     * share one right edge, so the edges decide first:
     * 1. the left anchor is the leftmost left edge of the bubbles nearer the left; the right anchor
     *    is the rightmost right edge of the bubbles nearer the right;
     * 2. a bubble on exactly one anchor is on that side (a wide incoming group bubble, pushed right
     *    by the avatar column, still starts at the left anchor), unless it is nearly centred and
     *    ticks are on screen;
     * 3. if any bubble on screen has an outgoing tick, a tick means me and no tick means not me;
     * 4. otherwise the side it is closer to, and left when it is centred.
     */
    fun resolve(
        bubbles: List<Bounds>,
        tickHints: List<Boolean>,
        area: Bounds,
    ): List<Side> {
        val nearer = bubbles.map { box -> gaps(box, area).let { (l, r) -> l.compareTo(r) } }
        val leftAnchor = bubbles.filterIndexed { i, _ -> nearer[i] < 0 }.minOfOrNull { it.left }
        val rightAnchor = bubbles.filterIndexed { i, _ -> nearer[i] > 0 }.maxOfOrNull { it.right }
        val ticksOnScreen = tickHints.any { it }
        return bubbles.mapIndexed { i, box ->
            val onLeft = leftAnchor != null && abs(box.left - leftAnchor) <= EDGE_MATCH
            val onRight = rightAnchor != null && abs(box.right - rightAnchor) <= EDGE_MATCH
            val anchorDecides = onLeft != onRight && (!ticksOnScreen || !isCentered(box, area))
            when {
                anchorDecides -> if (onLeft) Side.LEFT else Side.RIGHT
                ticksOnScreen -> if (tickHints[i]) Side.RIGHT else Side.LEFT
                nearer[i] > 0 -> Side.RIGHT
                else -> Side.LEFT
            }
        }
    }

    private const val EDGE_MATCH = 12
}
