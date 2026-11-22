package com.paragrudani.replyly.parser.x

import com.google.common.truth.Truth.assertWithMessage
import com.paragrudani.replyly.screen.Bounds
import com.paragrudani.replyly.screen.ScreenNode
import com.paragrudani.replyly.screen.ScreenSnapshot
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

/**
 * Property test for [XParser] on seeded random X screens (the same seed gives the same screen).
 *
 * The generator draws a reply composer (posts above the field), a thread detail view with an inline
 * composer (parents, the focal post with its absolute time line, other replies below it), or a quote
 * composer (the field, then the quoted card). Posts get 0 to 5 parents, random authors (one of them
 * me), texts that end in a mention ("Thanks @ravi_k"), multi-line texts and emoji, images with and
 * without alt text, link cards, their own "Replying to" line and an embedded quoted post. The thread
 * may be scrolled so the oldest post's header is cut off. The composer has a random draft (mentions
 * too), an optional "Replying to" line and an optional account handle. Each screen is built with view
 * ids, and without them (headers split over nodes or joined in one line).
 *
 * Invariants, checked against the generator's truth:
 * 1. Parents are exactly the visible posts above the target, oldest first.
 * 2. The target is: in the detail view, the focal post; in the reply composer, the post nearest above
 *    the composer (the generator only names that post in the "Replying to" line); in the quote
 *    composer, the quoted card.
 * 3. `isMine` holds exactly when the post's handle is my handle: the setting (any case, with or
 *    without `@`), else the handle on the composer's row; none when neither is known.
 * 4. No composer text (draft or hint), "Replying to" line or UI chrome (counts, buttons, the
 *    absolute time line) becomes post text: each post's text equals its generated text.
 * 5. A post embedded in another is that post's `quoted`, never a parent or the target.
 * 6. Other replies below the focal post, and a post whose header is scrolled off, are never read.
 * 7. Alt texts, images without alt, link cards and times match the generated ones.
 * 8. Both routes (view ids, geometry) read the same [XContext].
 */
class XParserPropertyTest {
    @Test
    fun `random X screens keep every invariant`() {
        val seen = mutableMapOf<String, Int>()
        repeat(RUNS) { i ->
            val seed = BASE_SEED + i
            val case = Gen(Random(seed)).case()
            val message = "seed $seed (${case.layout}, ${case.setting})"
            val byIds = XParser { case.setting }.explain(case.build(ids = true, joined = false))
            val bare = XParser { case.setting }.explain(case.build(ids = false, joined = case.joined))
            val ctx = checkNotNull(byIds.chat?.x) { "$message: ${byIds.notes}" }
            check(ctx, case, message)
            assertWithMessage("$message: geometry route equals view ids (8)").that(bare.chat?.x).isEqualTo(ctx)
            coverage(case, ctx).forEach { seen.merge(it, 1, Int::plus) }
        }
        // The generator must reach every shape it claims to, or the invariants above prove little.
        assertWithMessage("generator coverage $seen").that(seen.keys).containsAtLeastElementsIn(COVERAGE)
    }

    private fun coverage(
        case: Case,
        ctx: XContext,
    ): List<String> =
        buildList {
            add("layout ${case.layout}")
            if (case.cutFirst) add("scrolled")
            if (case.joined) add("joined headers")
            if (case.below.isNotEmpty()) add("replies below focal")
            if (ctx.parents.size >= 3) add("3+ parents")
            if (ctx.thread.any { it.quoted != null }) add("quote in thread")
            if (ctx.thread.any { it.isMine }) add("mine")
            if (ctx.myHandleSource == XContext.HandleSource.COMPOSER_ACCOUNT) add("mine from account")
            if (ctx.replyingTo.isNotEmpty()) add("replying line")
            if (ctx.thread.any { it.imageAltTexts.isNotEmpty() }) add("alt text")
            if (ctx.thread.any { it.linkCard != null }) add("link card")
            if (case.allPosts.any { it.text.endsWith("@ravi_k") || it.text.endsWith("@asha_dev") }) add("mention text")
            ctx.thread.mapNotNull { it.handle }.forEach { add("author $it") }
        }

    private fun check(
        ctx: XContext,
        case: Case,
        message: String,
    ) {
        val expectedMode = if (case.layout == Layout.QUOTE) XMode.QUOTE else XMode.REPLY
        assertWithMessage("$message mode").that(ctx.mode).isEqualTo(expectedMode)
        assertWithMessage("$message parents (1)")
            .that(ctx.parents.map { it.handle to it.text })
            .containsExactlyElementsIn(case.visibleParents.map { it.handle to it.text })
            .inOrder()
        assertWithMessage("$message target (2)")
            .that(ctx.target.handle to ctx.target.text)
            .isEqualTo(case.target.handle to case.target.text)
        val me = case.effectiveHandle
        for ((read, truth) in (ctx.parents + ctx.target).zip(case.visibleParents + case.target)) {
            assertWithMessage("$message mine (3) ${truth.handle}")
                .that(read.isMine)
                .isEqualTo(me != null && truth.handle.equals(me, ignoreCase = true))
            assertWithMessage("$message text (4)").that(read.text).isEqualTo(truth.text)
            assertWithMessage("$message name").that(read.authorName).isEqualTo(truth.name)
            assertWithMessage("$message time (7)").that(read.timeShown).isEqualTo(truth.timeShown)
            assertWithMessage("$message alts (7)").that(read.imageAltTexts).isEqualTo(truth.alts.filterNotNull())
            assertWithMessage("$message no-alt images (7)")
                .that(read.imagesWithoutAlt)
                .isEqualTo(truth.alts.count { it == null })
            assertWithMessage("$message card (7)")
                .that(read.linkCard)
                .isEqualTo(truth.card?.let { XLinkCard(it.first, it.second) })
            assertWithMessage("$message post replying-to")
                .that(read.replyingTo)
                .isEqualTo(listOfNotNull(truth.replyingTo))
            val quoted = truth.quote
            if (quoted == null) {
                assertWithMessage("$message no quote (5)").that(read.quoted).isNull()
            } else {
                assertWithMessage("$message quote (5)")
                    .that(read.quoted?.handle to read.quoted?.text)
                    .isEqualTo(quoted.handle to quoted.text)
            }
        }
        val readTexts = (ctx.thread + ctx.thread.mapNotNull { it.quoted }).map { it.text }
        for (hidden in case.neverRead) {
            assertWithMessage("$message never read (6)").that(readTexts).doesNotContain(hidden.text)
        }
        val quotedTexts = case.allPosts.mapNotNull { it.quote?.text }.toSet()
        assertWithMessage("$message quoted never a parent (5)")
            .that(
                ctx.parents.map { it.text }.filter {
                    it in quotedTexts &&
                        it !in case.visibleParents.map { p -> p.text }
                },
            ).isEmpty()
    }

    // ------------------------------------------------------------------ generator

    private enum class Layout { COMPOSER, DETAIL, QUOTE }

    private class Post(
        val name: String,
        val handle: String,
        val text: String,
        val time: String,
        val alts: List<String?> = emptyList(),
        val card: Pair<String, String>? = null,
        val replyingTo: String? = null,
        val quote: Post? = null,
        val absolute: String? = null,
    ) {
        val timeShown: String get() = absolute?.substringBeforeLast(" · ") ?: time
    }

    private class Case(
        val layout: Layout,
        val parents: List<Post>,
        val target: Post,
        val below: List<Post>,
        /** The oldest parent's header is scrolled off. */
        val cutFirst: Boolean,
        val setting: String?,
        val account: String?,
        val draft: String?,
        val composerReplying: String?,
        val joined: Boolean,
    ) {
        val visibleParents: List<Post> = if (cutFirst) parents.drop(1) else parents
        val neverRead: List<Post> = below + (if (cutFirst) parents.take(1) else emptyList())
        val allPosts: List<Post> = parents + target + below
        val effectiveHandle: String? =
            setting?.trim()?.trimStart('@')?.ifEmpty { null } ?: account

        fun build(
            ids: Boolean,
            joined: Boolean,
        ): ScreenSnapshot = Screen(ids, joined).build(this)
    }

    private class Gen(
        private val r: Random,
    ) {
        private val authors =
            listOf(
                "Asha Dev" to "asha_dev",
                "Ravi K" to "ravi_k",
                "Meera Iyer" to "meera_writes",
                "Dev N" to "dev_null_test",
                "Kiran" to ME,
            )
        private val texts =
            listOf(
                "Offline first is underrated.",
                "Thanks @ravi_k",
                "Great point @asha_dev",
                "We tried last-write-wins and regretted it.\nPer-field merge was worth it.",
                "Shipping the beta on Friday 🚀",
                "kal demo hai, ready?",
                "How are you handling sync conflicts?",
                "This is the way",
                "Agree with Meera here",
                "Small teams can still move fast, if they say no to most features and ship the rest.",
            )
        private val times = listOf("now", "45s", "5m", "2h", "23h", "3d", "Nov 19", "21 Nov 25")
        private val alts = listOf("A whiteboard sketch of boxes and arrows", "Screenshot of a settings screen", null)
        private val cards =
            listOf("How screen readers see your app" to "example.com", "Release notes for 2.0" to "blog.example.org")

        private var used = mutableSetOf<String>()

        /** Distinct texts on one screen, so every post can be told apart. */
        private fun text(): String {
            val free = texts.filter { it !in used }
            val t = if (free.isEmpty()) "Post number ${used.size + 1} here" else free.random(r)
            used += t
            return t
        }

        private fun post(
            withQuote: Boolean,
            absolute: Boolean = false,
            replying: Boolean = r.nextInt(3) == 0,
        ): Post {
            val (name, handle) = authors.random(r)
            val quote =
                if (withQuote && r.nextInt(4) == 0) {
                    val (qn, qh) = authors.random(r)
                    Post(qn, qh, text(), times.random(r))
                } else {
                    null
                }
            return Post(
                name = name,
                handle = handle,
                text = text(),
                time = times.random(r),
                alts = List(r.nextInt(3).let { if (it == 2) r.nextInt(1, 3) else 0 }) { alts.random(r) },
                card = if (r.nextInt(5) == 0) cards.random(r) else null,
                replyingTo = if (replying) authors.random(r).second else null,
                quote = quote,
                absolute = if (absolute) "6:42 PM · 21 Nov 26 · 1,204 Views" else null,
            )
        }

        fun case(): Case {
            used = mutableSetOf()
            val layout = Layout.entries.random(r)
            val parentCount = if (layout == Layout.QUOTE) 0 else r.nextInt(0, 6)
            val parents = List(parentCount) { post(withQuote = true) }
            // The quote composer's card shows no quote of its own.
            val target = post(withQuote = layout != Layout.QUOTE, absolute = layout == Layout.DETAIL, replying = false)
            val below = if (layout == Layout.DETAIL) List(r.nextInt(0, 3)) { post(withQuote = false) } else emptyList()
            val setting =
                when (r.nextInt(4)) {
                    0 -> "@" + ME.uppercase()
                    1 -> " kiran_TEST "
                    2 -> ""
                    else -> null
                }
            val account = if (r.nextBoolean()) ME else null
            val draft = listOf(null, "Per-field merge is the way", "cc @meera_writes", "lol").random(r)
            val replying =
                if (layout != Layout.QUOTE && layout != Layout.DETAIL && r.nextBoolean()) target.handle else null
            val detailReplying = if (layout == Layout.DETAIL && r.nextBoolean()) target.handle else null
            return Case(
                layout = layout,
                parents = parents,
                target = target,
                below = below,
                cutFirst = parents.size >= 2 && r.nextInt(3) == 0,
                setting = setting,
                account = account,
                draft = draft,
                composerReplying = replying ?: detailReplying,
                joined = r.nextBoolean(),
            )
        }
    }

    /** Lays a [Case] out like the synthetic fixtures (scripts/fixturegen/gen.py, class X). */
    private class Screen(
        private val ids: Boolean,
        private val joined: Boolean,
    ) {
        private fun id(name: String) = if (ids) "com.twitter.android:id/$name" else null

        private fun text(
            l: Int,
            t: Int,
            r: Int,
            b: Int,
            text: String,
            id: String? = null,
            clickable: Boolean = false,
        ) = ScreenNode(
            className = "android.widget.TextView",
            viewId = id?.let(::id),
            text = text,
            bounds = Bounds(l, t, r, b),
            clickable = clickable,
        )

        private fun group(
            b: Bounds,
            kids: List<ScreenNode>,
            id: String? = null,
        ) = ScreenNode(className = "android.view.ViewGroup", viewId = id?.let(::id), bounds = b, children = kids)

        private fun image(
            b: Bounds,
            cd: String?,
            id: String? = null,
        ) = ScreenNode(
            className = "android.widget.ImageView",
            viewId = id?.let(::id),
            contentDescription = cd,
            bounds = b,
        )

        private fun header(
            x: Int,
            y: Int,
            p: Post,
        ): List<ScreenNode> {
            if (joined) {
                val line = "${p.name} @${p.handle} · ${p.time}"
                return listOf(text(x, y, minOf(1040, x + 22 * line.length), y + 55, line))
            }
            val nw = 22 * p.name.length
            val hw = 20 * (p.handle.length + 1)
            return listOf(
                text(x, y, x + nw, y + 55, p.name, "tweet_header_name"),
                text(x + nw + 12, y, x + nw + 12 + hw, y + 55, "@${p.handle}", "tweet_header_username"),
                text(x + nw + hw + 24, y, x + nw + hw + 134, y + 55, "· ${p.time}", "tweet_header_timestamp"),
            )
        }

        private fun bodyHeight(text: String) = 55 * (maxOf(1, (text.length + 37) / 38) + text.count { it == '\n' })

        private fun actions(
            x: Int,
            y: Int,
        ): Pair<List<ScreenNode>, Int> {
            val kids = mutableListOf<ScreenNode>()
            listOf("Reply" to "3", "Repost" to "12", "Like" to "1.2K").forEachIndexed { i, (label, count) ->
                val left = x + i * 220
                kids +=
                    ScreenNode(
                        className = "android.widget.ImageButton",
                        contentDescription = label,
                        bounds =
                            Bounds(
                                left,
                                y,
                                left + 70,
                                y + 70,
                            ),
                        clickable = true,
                    )
                kids += text(left + 75, y + 8, left + 160, y + 62, count)
            }
            return kids to y + 80
        }

        private fun content(
            x: Int,
            yStart: Int,
            p: Post,
            kids: MutableList<ScreenNode>,
        ): Int {
            var y = yStart
            p.replyingTo?.let {
                kids += text(x, y, 1040, y + 50, "Replying to @$it", "reply_context_text")
                y += 60
            }
            val h = bodyHeight(p.text)
            kids += text(x, y, 1040, y + h, p.text, "tweet_content_text")
            y += h
            for (alt in p.alts) {
                kids += image(Bounds(x, y + 10, 1040, y + 430), alt ?: "Image", "tweet_media")
                y += 440
            }
            p.card?.let { (title, domain) ->
                val top = y + 10
                kids +=
                    ScreenNode(
                        className = "android.view.ViewGroup",
                        bounds = Bounds(x, top, 1040, top + 430),
                        clickable = true,
                        children =
                            listOf(
                                image(Bounds(x, top, 1040, top + 300), null),
                                text(x + 20, top + 310, 1020, top + 365, title, "card_title"),
                                text(x + 20, top + 370, 1020, top + 420, "From $domain", "card_domain"),
                            ),
                    )
                y = top + 440
            }
            return y
        }

        private fun quoteCard(
            x: Int,
            y: Int,
            q: Post,
        ): Pair<ScreenNode, Int> {
            val kids = mutableListOf(image(Bounds(x + 30, y + 20, x + 80, y + 70), q.name))
            kids += header(x + 100, y + 18, q)
            val end = content(x + 30, y + 85, q, kids)
            return group(Bounds(x, y, 1040, end + 20), kids, "quote_tweet_view") to end + 40
        }

        /** A compact row, or the focal post when [Post.absolute] is set. */
        private fun row(
            y: Int,
            p: Post,
        ): Pair<ScreenNode, Int> {
            val kids = mutableListOf(image(Bounds(40, y + 20, 150, y + 130), p.name))
            var cy: Int
            if (p.absolute != null) {
                kids += text(180, y + 25, 180 + 22 * p.name.length, y + 80, p.name, "tweet_header_name")
                kids +=
                    text(
                        180,
                        y + 85,
                        180 + 20 * (p.handle.length + 1),
                        y + 135,
                        "@${p.handle}",
                        "tweet_header_username",
                    )
                kids +=
                    ScreenNode(
                        className = "android.widget.Button",
                        text = "Follow",
                        bounds =
                            Bounds(
                                840,
                                y + 40,
                                1040,
                                y + 110,
                            ),
                        clickable = true,
                    )
                cy = content(40, y + 160, p, kids)
            } else {
                kids += header(180, y + 20, p)
                cy = content(180, y + 85, p, kids)
            }
            p.quote?.let {
                val (q, end) = quoteCard(180, cy + 10, it)
                kids += q
                cy = end
            }
            if (p.absolute != null) {
                kids += text(40, cy + 20, 1040, cy + 75, p.absolute, "tweet_date")
                cy += 90
                kids += text(40, cy, 300, cy + 55, "12 Reposts")
                kids += text(320, cy, 600, cy + 55, "48 Likes")
                cy += 70
            }
            val (acts, end) = actions(180, cy + 10)
            kids += acts
            return group(Bounds(0, y, 1080, end + 10), kids, "row") to end + 20
        }

        private fun composer(
            y0: Int,
            case: Case,
            hint: String,
        ): Pair<List<ScreenNode>, Int> {
            var y = y0
            val kids = mutableListOf<ScreenNode>()
            case.composerReplying?.let {
                kids += text(180, y, 1040, y + 50, "Replying to @$it", "reply_context_text")
                y += 60
            }
            kids +=
                image(
                    Bounds(40, y + 10, 150, y + 120),
                    case.account?.let { "Account @$it" } ?: "Profile picture",
                    "composer_avatar",
                )
            kids +=
                ScreenNode(
                    className = "android.widget.EditText",
                    viewId = id("tweet_text"),
                    text = case.draft ?: hint,
                    hint = hint,
                    bounds = Bounds(180, y, 1040, y + 130),
                    editable = true,
                    focused = true,
                    clickable = true,
                )
            return kids to y + 140
        }

        fun build(case: Case): ScreenSnapshot {
            val kids = mutableListOf<ScreenNode>()
            kids +=
                group(
                    Bounds(0, 110, 1080, 257),
                    listOf(text(150, 150, 500, 215, "Post"), text(840, 150, 1040, 215, "Reply", clickable = true)),
                )
            when (case.layout) {
                Layout.QUOTE -> {
                    val (comp, y) = composer(280, case, "Add a comment")
                    kids += comp
                    val (card, _) = quoteCard(180, y + 20, case.target)
                    kids += card
                }

                else -> {
                    var y = LIST_TOP + 13
                    val rows = mutableListOf<ScreenNode>()
                    for (p in case.parents + case.target + case.below) {
                        val (row, end) = row(y, p)
                        rows += row
                        y = end
                    }
                    var shown = rows.map { it }
                    if (case.cutFirst) {
                        // Scroll so the oldest post's header leaves the list; its body stays.
                        val shift = 120
                        shown = rows.map { shift(it, -shift) }.map { clip(it, LIST_TOP) }.filterNotNull()
                        y -= shift
                    }
                    kids += group(Bounds(0, LIST_TOP, 1080, y), shown)
                    val (comp, _) = composer(y + 10, case, "Post your reply")
                    kids += comp
                }
            }
            val root = group(Bounds(0, 0, 1080, maxOf(2340, kids.maxOf { it.bounds.bottom } + 10)), kids)
            return ScreenSnapshot(
                packageName = "com.twitter.android",
                capturedAt = Instant.parse("2026-11-21T18:15:30Z"),
                screenWidth = 1080,
                screenHeight = root.bounds.bottom,
                root = root,
            )
        }

        private fun shift(
            n: ScreenNode,
            dy: Int,
        ): ScreenNode =
            n.copy(
                bounds = Bounds(n.bounds.left, n.bounds.top + dy, n.bounds.right, n.bounds.bottom + dy),
                children = n.children.map { shift(it, dy) },
            )

        /** Drops nodes that start above [top], like a list that scrolled them away. */
        private fun clip(
            n: ScreenNode,
            top: Int,
        ): ScreenNode? {
            if (n.bounds.bottom <= top) return null
            val kids = n.children.mapNotNull { clip(it, top) }
            if (n.bounds.top < top && n.children.isEmpty()) return null
            return n.copy(
                bounds = Bounds(n.bounds.left, maxOf(top, n.bounds.top), n.bounds.right, n.bounds.bottom),
                children = kids,
            )
        }
    }

    private companion object {
        const val RUNS = 600
        val COVERAGE =
            listOf(
                "layout COMPOSER",
                "layout DETAIL",
                "layout QUOTE",
                "scrolled",
                "joined headers",
                "replies below focal",
                "3+ parents",
                "quote in thread",
                "mine",
                "mine from account",
                "replying line",
                "alt text",
                "link card",
                "mention text",
                "author asha_dev",
                "author ravi_k",
                "author meera_writes",
                "author dev_null_test",
                "author kiran_test",
            )
        const val BASE_SEED = 30_000L
        const val LIST_TOP = 257
        const val ME = "kiran_test"
    }
}
