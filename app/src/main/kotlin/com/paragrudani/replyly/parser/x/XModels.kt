package com.paragrudani.replyly.parser.x

/**
 * What the focused X composer is for (issue #30). Only these two modes read posts; X DMs go
 * through the generic parser as a normal 1:1 chat.
 */
enum class XMode {
    /** The reply composer: the full-screen one, or the inline one under a post's thread. */
    REPLY,

    /** The quote-post composer: my commentary above the quoted post. */
    QUOTE,
}

/** A link card under a post, as shown. Either part may be missing. */
data class XLinkCard(
    val title: String?,
    /** As shown, without a "From " prefix: `example.com`. */
    val domain: String?,
)

/**
 * One post as shown on screen. Every field is text from the screen (untrusted, written by
 * strangers): never log it.
 */
data class XPost(
    /** Display name as shown, `null` when not visible. */
    val authorName: String?,
    /** The @handle without the `@`, case as shown; `null` when not visible. */
    val handle: String?,
    /** The post text as shown; empty for a post with only media or a card. */
    val text: String,
    /** Time exactly as shown: relative ("2h", "Nov 21") or absolute ("6:42 PM · 21 Nov 26"). */
    val timeShown: String?,
    /** Alt text of each image, in screen order. An image without alt text is not listed here. */
    val imageAltTexts: List<String> = emptyList(),
    /** Images shown without alt text (counted, so the prompt can say "[image]"). */
    val imagesWithoutAlt: Int = 0,
    val linkCard: XLinkCard? = null,
    /** Handles (without `@`) from the post's own "Replying to @…" line, when it shows one. */
    val replyingTo: List<String> = emptyList(),
    /** A post embedded in this one (it quotes it). One level only: its own [quoted] is `null`. */
    val quoted: XPost? = null,
    /** The handle matches "My X handle" (or the composer's account). */
    val isMine: Boolean = false,
) {
    /** "Asha Dev (@asha_dev)", "Asha Dev", "@asha_dev" or "someone". */
    val authorLabel: String
        get() =
            when {
                authorName != null && handle != null -> "$authorName (@$handle)"
                authorName != null -> authorName
                handle != null -> "@$handle"
                else -> "someone"
            }
}

/**
 * The X screen behind a reply or quote composer, read on each tap (no history, issue #30 §3).
 *
 * Pinned for PR 2 (the X prompt mode). Shared contract: change it additively only.
 */
data class XContext(
    val mode: XMode,
    /**
     * [XMode.REPLY]: the post I am replying to. [XMode.QUOTE]: the post I am quoting. It is never
     * one of [parents], and a post embedded in it is in [XPost.quoted], never a parent.
     */
    val target: XPost,
    /** The visible thread posts above [target], oldest first. Always empty in [XMode.QUOTE]. */
    val parents: List<XPost> = emptyList(),
    /**
     * Handles (without `@`) from the composer's "Replying to @…" line, in the order shown. Empty
     * when the line is not visible, and in [XMode.QUOTE].
     */
    val replyingTo: List<String> = emptyList(),
    /** The handle that marked [XPost.isMine], without `@`; `null` when none was known. */
    val myHandle: String? = null,
    /** Where [myHandle] came from. */
    val myHandleSource: HandleSource = HandleSource.NONE,
    /**
     * How [target] was picked (#30 PR 2, additive). [TargetPick.NEAREST] is a guess: the X prompt
     * says so, and the model hedges.
     */
    val targetPick: TargetPick = TargetPick.UNKNOWN,
) {
    /** [parents], then [target]: the thread in screen order. */
    val thread: List<XPost> get() = parents + target

    enum class HandleSource { SETTING, COMPOSER_ACCOUNT, NONE }

    enum class TargetPick {
        /** The detail view's focal post (absolute time line): the post the composer answers. */
        FOCAL,

        /** The nearest post whose author the composer's "Replying to" line names. */
        NAMED,

        /** The nearest post above the composer: a guess. */
        NEAREST,

        /** The quoted card below the quote composer. */
        QUOTED_CARD,

        /** Not recorded (built by hand, or before #30 PR 2). */
        UNKNOWN,
    }
}
