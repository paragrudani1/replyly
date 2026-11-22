package com.paragrudani.replyly.parser.x

/**
 * Resource entry names in `com.twitter.android`. **Every one is a guess** (from older public
 * Twitter-for-Android dumps and the app's naming style) until the S23 dumps of
 * docs/DEVICE_CHECKLIST.md section 15 come back. When they do, fix the names here: the parser only
 * reads ids through this object, and falls back to text and geometry when none of them matches.
 */
internal object XIds {
    /** The composer's text field (new post, reply and quote share it). */
    val COMPOSER = setOf("tweet_text", "composer_edit_text")

    /** Search boxes: never a composer. */
    val SEARCH_FIELDS = setOf("query", "search_edit_text", "search_box")

    /** The DM conversation's text field. */
    val DM_FIELDS = setOf("dm_composer_edit_text", "dm_compose_text", "message_edit_text")

    /** Display name in a post header. */
    val NAME = setOf("tweet_header_name", "name_item", "user_name")

    /** "@handle" in a post header. */
    val HANDLE = setOf("tweet_header_username", "screenname_item", "user_screen_name")

    /** "· 2h" in a post header, or the detail view's absolute time line. */
    val TIME = setOf("tweet_header_timestamp", "timestamp", "tweet_date")

    /** The post text. */
    val TEXT = setOf("tweet_content_text", "tweet_text_view", "tweet_content")

    /** "Replying to @…" above a post's text or above the composer. */
    val REPLY_CONTEXT = setOf("reply_context_text", "tweet_reply_context", "in_reply_to")

    /** The card of a quoted post inside a post. */
    val QUOTE_CONTAINER = setOf("quote_tweet_view", "quoted_tweet", "tweet_quote")

    /** Link card parts. */
    val CARD_TITLE = setOf("card_title", "link_card_title")
    val CARD_DOMAIN = setOf("card_domain", "card_vanity_url", "link_card_domain")

    /** Images of a post (their description is the alt text, or "Image"). */
    val MEDIA = setOf("tweet_media", "media_image", "image_view")

    /** The account switcher or avatar next to the composer, whose text or description may hold my handle. */
    val COMPOSER_ACCOUNT = setOf("composer_account", "account_switcher", "composer_avatar")

    /** Ids whose presence means the view-id route applies. */
    val POST_MARKERS: Set<String> = HANDLE + TEXT
}
