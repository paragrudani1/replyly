package com.paragrudani.replyly.parser.x

import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.parser.ExplainingChatParser
import com.paragrudani.replyly.parser.ParseReport
import com.paragrudani.replyly.parser.ParsedChat
import com.paragrudani.replyly.parser.TargetApps
import com.paragrudani.replyly.parser.generic.GenericChatParser
import com.paragrudani.replyly.parser.generic.ScreenTree
import com.paragrudani.replyly.parser.generic.TreeNode
import com.paragrudani.replyly.screen.ScreenSnapshot

/**
 * X (issue #30). Reads the screen behind the focused reply or quote composer into an [XContext]
 * on [ParsedChat.x]; nothing is stored ([ParsedChat.historyApplies] is false).
 *
 * - **Composer.** The focused editable field (else the first one whose hint is a reply or quote
 *   hint), classified by [XFields]. A new-post composer, search or any other field is not a reply:
 *   `null`. A field [XFields] cannot classify still counts as a reply when a "Replying to @…" line
 *   sits right above it, and the notes say so ([NOTE_UNRECOGNISED_FIELD]): the overlay's gate does
 *   not show the button on such a field. A DM goes to [GenericChatParser] as a normal 1:1 chat.
 * - **Posts** ([XPostReader]): headers, bodies, alt text, link card, "Replying to" lines, the
 *   quoted post embedded in a post.
 * - **Target.** Reply: the detail view's focal post (the one with an absolute time line) if one
 *   is above the composer; else the nearest post above the composer whose author the composer's
 *   "Replying to" line names; else the nearest post above the composer. Posts between the target
 *   and the composer (other replies) are dropped; the posts above the target are its parents,
 *   oldest first. Quote: the first post below the top of the composer (the quoted card), else the
 *   nearest above; no parents.
 * - **Mine.** A post is mine when its handle equals [myHandle] (the "My X handle" setting, `@` and
 *   case ignored), or, with the setting empty, the handle shown on the composer's row.
 * - **Messages.** [ParsedChat.messages] (PR 1's interim prompt path, now read only by the debug
 *   dump; the X prompt reads [XContext]) = parents then the target, mine
 *   marked; the title is the target's author; not a group. A post by someone other than the
 *   target's author says so in a bracketed note, since a 1:1 prompt names every other line after
 *   the title. Alt text, link cards and quoted posts become short bracketed notes.
 */
class XParser(
    /** "My X handle" as stored (no `@`), read on each parse; blank or `null` when not set. */
    private val myHandle: () -> String? = { null },
) : ExplainingChatParser {
    override val packages: Set<String> = TargetApps.X_PACKAGES

    override fun explain(snapshot: ScreenSnapshot): ParseReport {
        val tree = ScreenTree(snapshot)
        val found = composer(tree) ?: return ParseReport(null, METHOD_GEOMETRY, listOf("no reply or quote composer"))
        val composer = found.node
        val field = found.field
        if (field == XField.DM) {
            val generic = GenericChatParser().explain(snapshot)
            return ParseReport(generic.chat, METHOD_DM, listOf("direct message: generic 1:1 path") + generic.notes)
        }
        if (field != XField.REPLY && field != XField.QUOTE) {
            return ParseReport(null, METHOD_GEOMETRY, listOf("focused field is ${field.name.lowercase()}: no target"))
        }
        val mode = if (field == XField.QUOTE) XMode.QUOTE else XMode.REPLY
        val reader = XPostReader(tree, composer)
        val method = if (reader.usesIds) METHOD_VIEW_IDS else METHOD_GEOMETRY
        val replyLine = if (mode == XMode.REPLY) reader.composerReplyingTo else null
        val replyingTo = replyLine?.text?.let(XText::replyingToHandles).orEmpty()

        val pick =
            when (mode) {
                XMode.REPLY -> pickReplyTarget(reader, composer, replyingTo)
                XMode.QUOTE -> pickQuoteTarget(reader, composer, tree.screen.bottom)
            } ?: return ParseReport(null, method, listOf("mode: ${mode.name.lowercase()}", "no post found"))

        val setting = XHandles.normalize(myHandle())?.ifEmpty { null }
        val account = if (setting == null) reader.composerAccount() else null
        val handle = setting ?: account
        val source =
            when {
                setting != null -> XContext.HandleSource.SETTING
                account != null -> XContext.HandleSource.COMPOSER_ACCOUNT
                else -> XContext.HandleSource.NONE
            }
        val context =
            XContext(
                mode = mode,
                target = pick.target.markMine(handle),
                parents = pick.parents.map { it.markMine(handle) },
                replyingTo = replyingTo,
                myHandle = handle,
                myHandleSource = source,
                targetPick = pick.pick,
            )
        val chat = chat(snapshot, context)
        val notes =
            listOf(
                "mode: ${mode.name.lowercase()}",
                "${context.thread.size} posts: ${context.parents.size} parents + target" +
                    (if (context.target.quoted != null) ", target quotes a post" else ""),
                "target: ${pick.how}",
                "replying-to line: ${if (replyLine != null) "${replyingTo.size} handles" else "not shown"}",
                "${context.thread.count { it.isMine }} mine (handle from ${source.name.lowercase()})",
                "${context.thread.sumOf { it.imageAltTexts.size }} alt texts, " +
                    "${context.thread.count { it.linkCard != null }} link cards",
            ) + listOfNotNull(NOTE_UNRECOGNISED_FIELD.takeIf { found.inferred })
        return ParseReport(chat, method, notes)
    }

    private class Pick(
        val target: XPost,
        val parents: List<XPost>,
        val how: String,
        val pick: XContext.TargetPick,
    )

    private fun pickReplyTarget(
        reader: XPostReader,
        composer: TreeNode,
        replyingTo: List<String>,
    ): Pick? {
        val above = reader.anchors.filter { it.top < composer.bounds.top }
        val posts = reader.posts(above, composer.bounds.top)
        if (posts.isEmpty()) return null
        val focal = posts.indexOfLast { it.isFocal }
        val named =
            posts.indexOfLast { p -> replyingTo.any { XHandles.same(it, p.post.handle) } }
        val (index, pick) =
            when {
                focal >= 0 -> focal to XContext.TargetPick.FOCAL
                named >= 0 -> named to XContext.TargetPick.NAMED
                else -> posts.lastIndex to XContext.TargetPick.NEAREST
            }
        val how =
            when (pick) {
                XContext.TargetPick.FOCAL -> "focal post"
                XContext.TargetPick.NAMED -> "named in replying-to line"
                else -> "nearest above composer"
            }
        return Pick(posts[index].post, posts.take(index).map { it.post }, how, pick)
    }

    private fun pickQuoteTarget(
        reader: XPostReader,
        composer: TreeNode,
        screenBottom: Int,
    ): Pick? {
        val below = reader.anchors.filter { it.top >= composer.bounds.top }
        reader.posts(below, screenBottom).firstOrNull()?.let {
            return Pick(it.post, emptyList(), "quoted card below composer", XContext.TargetPick.QUOTED_CARD)
        }
        val above = reader.anchors.filter { it.top < composer.bounds.top }
        return reader
            .posts(above, composer.bounds.top)
            .lastOrNull()
            ?.let { Pick(it.post, emptyList(), "nearest above composer", XContext.TargetPick.NEAREST) }
    }

    private class Composer(
        val node: TreeNode,
        val field: XField,
        /** [XFields] could not classify the field; a "Replying to" line above it made it a reply. */
        val inferred: Boolean = false,
    )

    private fun composer(tree: ScreenTree): Composer? {
        val fields = tree.shownNodes.filter { it.editable && !it.node.password }
        val focused = fields.firstOrNull { it.node.focused }
        if (focused != null) {
            val field = XFields.classify(focused.node.viewId, focused.node.hint)
            if (field == XField.OTHER && replyLineAbove(tree, focused)) {
                return Composer(focused, XField.REPLY, inferred = true)
            }
            return Composer(focused, field)
        }
        return fields
            .map { Composer(it, XFields.classify(it.node.viewId, it.node.hint)) }
            .firstOrNull { it.field == XField.REPLY || it.field == XField.QUOTE }
    }

    /** A "Replying to @…" line right above an unknown field: the field is a reply composer. */
    private fun replyLineAbove(
        tree: ScreenTree,
        field: TreeNode,
    ): Boolean =
        tree.textLeaves().any {
            XText.isReplyingTo(it.text!!) &&
                it.bounds.bottom <= field.bounds.top + REPLY_LINE_SLACK &&
                it.bounds.bottom >= field.bounds.top - REPLY_LINE_REACH
        }

    private fun XPost.markMine(handle: String?): XPost =
        copy(isMine = XHandles.same(this.handle, handle), quoted = quoted?.markMine(handle))

    private fun chat(
        snapshot: ScreenSnapshot,
        context: XContext,
    ): ParsedChat {
        val target = context.target
        val title = target.authorName ?: target.handle?.let { "@$it" } ?: "X"
        val messages =
            context.thread.map { post ->
                ChatMessage(
                    sender = if (post.isMine) null else post.authorName ?: post.handle?.let { "@$it" },
                    text = interimText(post, target),
                    timeShown = post.timeShown,
                    isMe = post.isMine,
                )
            }
        return ParsedChat(
            packageName = snapshot.packageName,
            title = title,
            isGroup = false,
            messages = messages,
            historyApplies = false,
            x = context,
        )
    }

    companion object {
        const val METHOD_VIEW_IDS = "x:view-ids"
        const val METHOD_GEOMETRY = "x:geometry"
        const val METHOD_DM = "x:dm-generic"

        /**
         * The parser read the field as a reply only from the "Replying to" line; the overlay's gate
         * sees the field alone ([XFields]), so the phone showed no button on it.
         */
        const val NOTE_UNRECOGNISED_FIELD =
            "field not recognised (id and hint): reply inferred from the replying-to line; no button on the phone"

        private const val REPLY_LINE_SLACK = 8
        private const val REPLY_LINE_REACH = 300

        /**
         * One post as message text for the interim (v2) prompt: "[by Ravi K (@ravi_k)]" when the
         * author is not the target's, the text, then "[image: alt]", "[image]", "[link: title
         * (domain)]" and "[quotes Asha (@asha_dev): text]".
         */
        fun interimText(
            post: XPost,
            target: XPost,
        ): String {
            val parts = mutableListOf<String>()
            val sameAuthor =
                post === target ||
                    if (post.handle != null) {
                        XHandles.same(post.handle, target.handle)
                    } else {
                        post.authorName == target.authorName
                    }
            if (!post.isMine && !sameAuthor) parts += "[by ${post.authorLabel}]"
            post.text.takeIf { it.isNotBlank() }?.let { parts += it }
            parts += notes(post)
            post.quoted?.let { q ->
                val body = (listOfNotNull(q.text.takeIf { it.isNotBlank() }) + notes(q)).joinToString(" ")
                parts += "[quotes ${q.authorLabel}: ${body.ifEmpty { "media" }}]"
            }
            return parts.joinToString(" ").ifEmpty { "[post without text]" }
        }

        private fun notes(post: XPost): List<String> =
            buildList {
                post.imageAltTexts.forEach { add("[image: $it]") }
                repeat(post.imagesWithoutAlt) { add("[image]") }
                post.linkCard?.let { card ->
                    val title = card.title ?: "link"
                    add(if (card.domain != null) "[link: $title (${card.domain})]" else "[link: $title]")
                }
            }
    }
}
