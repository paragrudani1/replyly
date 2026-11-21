package com.paragrudani.replyly.parser

/** Apps with a full parser (and history). Every other app goes to the generic parser. */
object TargetApps {
    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    const val TELEGRAM = "org.telegram.messenger"

    val FULL_PARSER_PACKAGES: Set<String> = setOf(WHATSAPP, WHATSAPP_BUSINESS, TELEGRAM)
}
