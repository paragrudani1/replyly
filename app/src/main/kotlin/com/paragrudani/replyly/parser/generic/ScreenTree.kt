package com.paragrudani.replyly.parser.generic

import com.paragrudani.replyly.screen.Bounds
import com.paragrudani.replyly.screen.ScreenNode
import com.paragrudani.replyly.screen.ScreenSnapshot

/** A [ScreenNode] with a parent link, so parsers can walk up as well as down. Compared by identity. */
class TreeNode internal constructor(
    val node: ScreenNode,
    val parent: TreeNode?,
    val depth: Int,
) {
    /** Visible to the user, along with every ancestor. Set before [children]: they read it. */
    val shown: Boolean = node.visible && (parent?.shown ?: true)

    val children: List<TreeNode> = node.children.map { TreeNode(it, this, depth + 1) }

    val bounds: Bounds get() = node.bounds

    /** Resource entry name: `message_text` for `com.whatsapp:id/message_text`. */
    val idName: String? = node.viewId?.substringAfter(":id/", missingDelimiterValue = "")?.ifEmpty { null }

    /** Cleaned text ([ChatText.clean]), `null` when blank. */
    val text: String? = ChatText.clean(node.text)

    /** Cleaned content description, `null` when blank. */
    val description: String? = ChatText.clean(node.contentDescription)

    val editable: Boolean get() = node.editable

    fun descendantsAndSelf(): Sequence<TreeNode> =
        sequence {
            yield(this@TreeNode)
            children.forEach { yieldAll(it.descendantsAndSelf()) }
        }

    /** Parent first, root last. */
    fun ancestors(): Sequence<TreeNode> = generateSequence(parent) { it.parent }

    fun isAncestorOf(other: TreeNode): Boolean = other.ancestors().any { it === this }

    private val hasTextBelow: Boolean by lazy {
        children.any { it.shown && (it.text != null || it.hasTextBelow) }
    }

    /** A shown, non-editable node with text and no shown text below it: one piece of visible text. */
    val isTextLeaf: Boolean get() = shown && text != null && !editable && !hasTextBelow && bounds.hasArea
}

/** The whole tree of one snapshot, with lookups the parsers share. */
class ScreenTree(
    val snapshot: ScreenSnapshot,
) {
    val root: TreeNode = TreeNode(snapshot.root, null, 0)

    /** Every shown node, depth first. */
    val shownNodes: List<TreeNode> = root.descendantsAndSelf().filter { it.shown }.toList()

    val screen: Bounds = Bounds(0, 0, snapshot.screenWidth, snapshot.screenHeight)

    /** Shown nodes whose resource entry name is [name], in reading order. */
    fun byIdName(name: String): List<TreeNode> = shownNodes.filter { it.idName == name }.sortedWith(readingOrder)

    fun textLeaves(under: TreeNode = root): List<TreeNode> =
        under
            .descendantsAndSelf()
            .filter { it.isTextLeaf }
            .sortedWith(readingOrder)
            .toList()

    companion object {
        val readingOrder: Comparator<TreeNode> = compareBy<TreeNode>({ it.bounds.top }, { it.bounds.left })

        /** Deepest node that contains all of [nodes] (a node counts as containing itself). */
        fun lowestCommonAncestor(nodes: List<TreeNode>): TreeNode? {
            if (nodes.isEmpty()) return null
            var candidate: TreeNode? = nodes.first()
            while (candidate != null) {
                val c = candidate
                if (nodes.all { it === c || c.isAncestorOf(it) }) return c
                candidate = c.parent
            }
            return null
        }
    }
}

internal val Bounds.hasArea: Boolean get() = width > 0 && height > 0

internal fun Bounds.union(other: Bounds): Bounds =
    Bounds(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))

internal fun List<Bounds>.union(): Bounds = reduce { a, b -> a.union(b) }

internal fun Bounds.overlapsVertically(other: Bounds): Boolean = bottom > other.top && top < other.bottom
