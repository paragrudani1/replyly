package com.paragrudani.replyly.parser

/** Apps with their own parser. Every other app goes to the generic parser. */
object TargetApps {
    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    const val TELEGRAM = "org.telegram.messenger"

    val FULL_PARSER_PACKAGES: Set<String> = setOf(WHATSAPP, WHATSAPP_BUSINESS, TELEGRAM)

    /** X, formerly Twitter (issue #30): its own parser, but no history and no passive capture. */
    const val X = "com.twitter.android"

    val X_PACKAGES: Set<String> = setOf(X)

    /** Apps whose events and chat trees the accessibility service handles. */
    val ACCESSIBILITY_PACKAGES: Set<String> = FULL_PARSER_PACKAGES + X_PACKAGES
}
