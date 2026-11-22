package com.paragrudani.replyly.parser.x

import com.paragrudani.replyly.parser.generic.ChatText
import com.paragrudani.replyly.parser.generic.ScreenTree
import com.paragrudani.replyly.parser.generic.TreeNode
import com.paragrudani.replyly.parser.generic.hasArea
import com.paragrudani.replyly.parser.generic.overlapsVertically
import com.paragrudani.replyly.screen.Bounds

/**
 * Reads the posts around one X composer. Each post starts at a header (a name, an `@handle` and a
 * time, in one node or split over a row; the detail view's focal post stacks the name above the
 * handle) and runs down to the next header. A header indented past the main column, or inside a
 * quote card id, starts the post embedded in the one above it.
 *
 * View ids first ([XIds]: name, time, text, card, quote card), each piece falling back to text
 * patterns ([XText]) and geometry when its id is missing.
 */
internal class XPostReader(
    private val tree: ScreenTree,
    private val composer: TreeNode,
) {
    /** At least one post id is on screen: the route the debug dump reports. */
    val usesIds: Boolean = tree.shownNodes.any { it.idName in XIds.POST_MARKERS }

    private val screen = tree.screen
    private val embedIndent = maxOf(MIN_EMBED_INDENT, screen.width / EMBED_INDENT_DIVISOR)

    /** Text leaves outside the composer and off its row. */
    private val leaves: List<TreeNode> =
        tree
            .textLeaves()
            .filter { !inComposer(it) && !onComposerRow(it.bounds) && !inEditable(it) }

    private val media: List<TreeNode> =
        tree.shownNodes
            .filter { node ->
                node.description != null && node.text == null && node.bounds.hasArea && !inComposer(node) &&
                    !onComposerRow(node.bounds) &&
                    (
                        node.idName in XIds.MEDIA ||
                            (isImageClass(node) && node.bounds.width >= screen.width * MEDIA_MIN_WIDTH)
                    )
            }.sortedWith(ScreenTree.readingOrder)

    class Anchor(
        val node: TreeNode,
        val header: XText.Header,
        val nameNode: TreeNode?,
        val timeNode: TreeNode?,
        val inQuoteCard: Boolean,
    ) {
        val top: Int = minOf(node.bounds.top, nameNode?.bounds?.top ?: Int.MAX_VALUE)
        val left: Int = nameNode?.bounds?.left?.let { minOf(it, node.bounds.left) } ?: node.bounds.left
        val name: String? get() = header.name ?: nameNode?.text
        val time: String? get() = header.time ?: timeNode?.text?.let(XText::timePart)
        val pieces: List<TreeNode> get() = listOfNotNull(node, nameNode, timeNode)
    }

    /** Every header on screen outside the composer, in reading order. */
    val anchors: List<Anchor> by lazy { findAnchors() }

    /** The composer's "Replying to" line: the nearest one above it with no post content in between. */
    val composerReplyingTo: TreeNode? by lazy {
        val above =
            leaves
                .filter {
                    it.bounds.bottom <= composer.bounds.top + ROW_SLACK &&
                        it.bounds.bottom >= composer.bounds.top - COMPOSER_LINE_REACH
                }
        val line = above.lastOrNull { isReplyingToNode(it) } ?: return@lazy null
        val between =
            above.filter { it.bounds.top > line.bounds.top && !XText.isChrome(it.text!!) && !isReplyingToNode(it) }
        line.takeIf {
            between.isEmpty() &&
                anchors.none { a ->
                    a.top > line.bounds.top && a.top < composer.bounds.top
                }
        }
    }

    /**
     * [candidates] (headers in reading order) as main posts, each with the post embedded in it.
     * [end] is where the last post's content stops.
     */
    fun posts(
        candidates: List<Anchor>,
        end: Int,
    ): List<ReadPost> {
        if (candidates.isEmpty()) return emptyList()
        // A lone quote card (the quote composer's) is a main post, not an embedded one.
        val outer = candidates.filterNot { it.inQuoteCard }
        val mainLeft = outer.ifEmpty { candidates }.minOf { it.left }
        val out = mutableListOf<ReadPost>()
        var current: MutableList<Anchor>? = null
        val groups = mutableListOf<MutableList<Anchor>>()
        for (a in candidates) {
            val embedded = (outer.isNotEmpty() && a.inQuoteCard) || a.left > mainLeft + embedIndent
            when {
                !embedded -> {
                    current = mutableListOf(a)
                    groups += current
                }

                // The post that embeds it is scrolled off above: neither of them is read.
                current == null -> {
                    Unit
                }

                else -> {
                    current.add(a)
                }
            }
        }
        val allPieces = anchors.flatMap { it.pieces }.toSet()
        groups.forEachIndexed { i, group ->
            val bandEnd = groups.getOrNull(i + 1)?.first()?.top ?: end
            val main = group.first()
            val quote = group.getOrNull(1)
            val mainEnd = quote?.top ?: bandEnd
            val quoted = quote?.let { read(it, it.top, bandEnd, allPieces, quoted = null) }
            // The focal post's absolute time line comes after its quote card, if it has one.
            val absolute = band(main.top, bandEnd).firstOrNull { XText.isAbsoluteTime(it.text!!) }
            val post = read(main, main.top, mainEnd, allPieces, quoted)
            val timed =
                if (post.timeShown == null &&
                    absolute != null
                ) {
                    post.copy(timeShown = absoluteTime(absolute.text!!))
                } else {
                    post
                }
            out += ReadPost(timed, main, isFocal = absolute != null)
        }
        return out
    }

    class ReadPost(
        val post: XPost,
        val anchor: Anchor,
        /** The detail view's focal post: it shows an absolute time line. */
        val isFocal: Boolean,
    )

    /** A handle shown on the composer's row or in an account id: the composer's account. */
    fun composerAccount(): String? {
        val nodes =
            tree.shownNodes.filter { node ->
                !inComposer(node) &&
                    (node.idName in XIds.COMPOSER_ACCOUNT || onComposerRow(node.bounds))
            }
        return nodes
            .flatMap { node -> listOfNotNull(node.text, node.description) }
            .filterNot(XText::isReplyingTo)
            .flatMap(XHandles::findAll)
            .distinctBy { it.lowercase() }
            .singleOrNull()
    }

    private fun band(
        start: Int,
        end: Int,
    ): List<TreeNode> = leaves.filter { it.bounds.top >= start && it.bounds.top < end }

    private fun read(
        anchor: Anchor,
        start: Int,
        end: Int,
        allPieces: Set<TreeNode>,
        quoted: XPost?,
    ): XPost {
        val inBand = band(start, end).filter { it !in allPieces && it !== composerReplyingTo }
        val replying = inBand.filter(::isReplyingToNode)
        val absolute = inBand.firstOrNull { XText.isAbsoluteTime(it.text!!) }
        val card = card(inBand)
        val used = (replying + listOfNotNull(absolute) + card.nodes).toSet()
        val rest = inBand.filter { it !in used }
        val idTexts = rest.filter { it.idName in XIds.TEXT }
        val textNodes = idTexts.ifEmpty { rest.filter { !XText.isChrome(it.text!!) && !XText.isAbsoluteTime(it.text) } }
        val images = media.filter { it.bounds.top >= start && it.bounds.top < end }
        val alts = images.mapNotNull { XText.altText(it.description!!) }
        return XPost(
            authorName = anchor.name,
            handle = anchor.header.handle,
            text = textNodes.joinToString("\n") { it.text!! },
            timeShown = anchor.time ?: absolute?.text?.let(::absoluteTime),
            imageAltTexts = alts,
            imagesWithoutAlt = images.size - alts.size,
            linkCard = card.card,
            replyingTo = replying.flatMap { XText.replyingToHandles(it.text!!) },
            quoted = quoted,
        )
    }

    private class Card(
        val card: XLinkCard?,
        val nodes: List<TreeNode>,
    )

    private fun card(inBand: List<TreeNode>): Card {
        val idTitle = inBand.firstOrNull { it.idName in XIds.CARD_TITLE }
        val idDomain = inBand.firstOrNull { it.idName in XIds.CARD_DOMAIN }
        if (idTitle != null || idDomain != null) {
            return Card(
                XLinkCard(idTitle?.text, idDomain?.text?.let { XText.domainOf(it) ?: it }),
                listOfNotNull(idTitle, idDomain),
            )
        }
        val domain =
            inBand.firstOrNull { it.idName !in XIds.TEXT && XText.domainOf(it.text!!) != null }
                ?: return Card(null, emptyList())
        val title =
            domain.parent
                ?.children
                ?.flatMap { it.descendantsAndSelf().toList() }
                ?.firstOrNull { it !== domain && it in inBand && !XText.isChrome(it.text!!) }
        return Card(XLinkCard(title?.text, XText.domainOf(domain.text!!)), listOfNotNull(title, domain))
    }

    /** "6:42 PM · 21 Nov 26 · 1,204 Views" gives "6:42 PM · 21 Nov 26". */
    private fun absoluteTime(line: String): String =
        ChatText
            .squash(line)
            .split('·')
            .take(2)
            .joinToString(" · ") { it.trim() }

    private fun findAnchors(): List<Anchor> {
        val textIdNodes = tree.shownNodes.filter { it.idName in XIds.TEXT }
        val heads =
            leaves.mapNotNull { leaf ->
                if (leaf.idName in XIds.TEXT || textIdNodes.any { it.isAncestorOf(leaf) }) return@mapNotNull null
                if (leaf.idName in XIds.NAME || isReplyingToNode(leaf)) return@mapNotNull null
                XText.header(leaf.text!!)?.let { leaf to it }
            }
        val headNodes = heads.map { it.first }.toSet()
        return heads.mapNotNull { (node, header) ->
            val name = if (header.name == null) nameFor(node, headNodes) else null
            val time = if (header.time == null) timeFor(node, headNodes) else null
            // A mention at the end of a line ("Thanks @ravi_k") has neither: not a header.
            val sure = header.time != null || time != null || (header.name == null && name != null)
            if (!sure) return@mapNotNull null
            Anchor(node, header, name, time, node.ancestors().any { it.idName in XIds.QUOTE_CONTAINER })
        }
    }

    /** The name of a header whose line has none: the id, else the row's left neighbour, else the line above. */
    private fun nameFor(
        handle: TreeNode,
        heads: Set<TreeNode>,
    ): TreeNode? {
        val candidates = leaves.filter { it !== handle && it !in heads && !isReplyingToNode(it) }
        val sameRow =
            candidates.filter {
                sameRow(it.bounds, handle.bounds) &&
                    it.bounds.right <= handle.bounds.left + ROW_SLACK
            }
        val above =
            candidates.filter {
                it.bounds.bottom in (handle.bounds.top - STACK_GAP)..(handle.bounds.top + ROW_SLACK) &&
                    !sameRow(it.bounds, handle.bounds) &&
                    kotlin.math.abs(it.bounds.left - handle.bounds.left) <= STACK_ALIGN
            }
        val byId = (sameRow + above).filter { it.idName in XIds.NAME }
        val plain = { list: List<TreeNode> -> list.filter { looksLikeName(it) } }
        return byId.maxByOrNull { it.bounds.right }
            ?: plain(sameRow).maxByOrNull { it.bounds.right }
            ?: plain(above).maxByOrNull { it.bounds.bottom }
    }

    private fun timeFor(
        handle: TreeNode,
        heads: Set<TreeNode>,
    ): TreeNode? =
        leaves
            .filter {
                it !== handle && it !in heads && sameRow(it.bounds, handle.bounds) &&
                    it.bounds.left >= handle.bounds.right - ROW_SLACK
            }.filter {
                (it.idName in XIds.TIME && XText.isPostTime(it.text!!)) || XText.timePart(it.text!!) != null
            }.minByOrNull { it.bounds.left }

    private fun looksLikeName(node: TreeNode): Boolean {
        val text = node.text!!
        return node.idName !in XIds.TEXT && !XText.isChrome(text) && !XText.isPostTime(text) &&
            !XText.isAbsoluteTime(text) && '\n' !in text && text.length <= MAX_NAME && XText.header(text) == null
    }

    private fun isReplyingToNode(node: TreeNode): Boolean = node.text?.let(XText::isReplyingTo) == true

    private fun inComposer(node: TreeNode): Boolean = node === composer || composer.isAncestorOf(node)

    private fun inEditable(node: TreeNode): Boolean = node.editable || node.ancestors().any { it.editable }

    /** Overlaps the composer's band by at least half of the smaller height: the avatar or account next to it. */
    private fun onComposerRow(b: Bounds): Boolean = sameRow(b, composer.bounds)

    private fun sameRow(
        a: Bounds,
        b: Bounds,
    ): Boolean {
        if (!a.overlapsVertically(b)) return false
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return overlap * 2 >= minOf(a.height, b.height)
    }

    private fun isImageClass(node: TreeNode): Boolean = node.node.className?.contains("Image") == true

    private companion object {
        const val ROW_SLACK = 8
        const val STACK_GAP = 80
        const val STACK_ALIGN = 60
        const val COMPOSER_LINE_REACH = 300
        const val MIN_EMBED_INDENT = 24
        const val EMBED_INDENT_DIVISOR = 30
        const val MEDIA_MIN_WIDTH = 0.35
        const val MAX_NAME = 50
    }
}
