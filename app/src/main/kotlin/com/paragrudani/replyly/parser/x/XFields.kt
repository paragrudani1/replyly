package com.paragrudani.replyly.parser.x

import com.paragrudani.replyly.parser.TargetApps
import com.paragrudani.replyly.parser.generic.ChatText

/** What a focused text field in the X app is for. */
enum class XField {
    /** The reply composer (full screen, or inline under a post): the button shows. */
    REPLY,

    /** The quote-post composer: the button shows. */
    QUOTE,

    /** A direct message: the button shows, the generic parser reads it. */
    DM,

    /** A new post with no target ("What's happening?"): no button. */
    NEW_POST,

    /** Search, Grok, settings, sign-in...: no button. */
    SEARCH,

    /** Unknown: no button (precision first; the device checklist catches a renamed field). */
    OTHER,
}

/**
 * Detection (issue #30 §1) from the focused field alone: its view id and hint. The overlay runs it
 * on every focus or window change, so it reads no tree. The hint stays readable after the user
 * types (`AccessibilityNodeInfo.getHintText`); an empty field also reports the hint as its text.
 *
 * Hints are the English UI's. Every view id is a guess until the S23 dumps of DEVICE_CHECKLIST 15
 * come back ([XIds]).
 */
object XFields {
    private val replyHints =
        listOf(
            Regex("""^(?:post|tweet) your reply\b.*"""),
            Regex("""^reply to @?[a-z0-9_]+.*"""),
            Regex("""^add (?:your|a) reply\b.*"""),
        )
    private val quoteHints = listOf(Regex("""^add a comment\b.*"""))
    private val newPostHints =
        listOf(
            Regex("""^what[’']?s happening\b.*"""),
            Regex("""^what is happening\b.*"""),
            Regex("""^add another (?:post|tweet)\b.*"""),
        )
    private val dmHints = listOf(Regex("""^start a (?:new )?message\b.*"""), Regex("""^message\b.*"""))
    private val searchHints = listOf(Regex("""^search\b.*"""), Regex("""^ask (?:grok|anything)\b.*"""))

    fun classify(
        viewId: String?,
        hint: String?,
    ): XField {
        val id = viewId?.substringAfter(":id/", missingDelimiterValue = "")?.ifEmpty { null }
        val h = hint?.let { ChatText.squash(it).lowercase() }?.ifEmpty { null }
        if (id in XIds.SEARCH_FIELDS) return XField.SEARCH
        if (id in XIds.DM_FIELDS) return XField.DM
        if (h != null) {
            when {
                replyHints.any { it.matches(h) } -> return XField.REPLY
                quoteHints.any { it.matches(h) } -> return XField.QUOTE
                newPostHints.any { it.matches(h) } -> return XField.NEW_POST
                searchHints.any { it.matches(h) } -> return XField.SEARCH
                dmHints.any { it.matches(h) } -> return XField.DM
            }
        }
        return XField.OTHER
    }

    /**
     * The overlay's gate: `true` for every app but X; on X only for the reply composer, the quote
     * composer and a DM.
     */
    fun allowsButton(
        packageName: String,
        viewId: String?,
        hint: String?,
    ): Boolean =
        packageName !in TargetApps.X_PACKAGES ||
            classify(viewId, hint) in setOf(XField.REPLY, XField.QUOTE, XField.DM)
}
