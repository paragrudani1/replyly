package com.paragrudani.replyly.parser

import com.paragrudani.replyly.screen.ScreenSnapshot

// Owned by ticket #2. Register the real parsers here; AppGraph only calls Parsers.registry().
object Parsers {
    fun registry(): ChatParserRegistry =
        ChatParserRegistry(
            parsers = emptyList(), // TODO(#2): WhatsApp (+ Business) and Telegram parsers.
            fallback = UnimplementedGenericParser,
        )
}

/** Placeholder until #2 lands the generic parser. Never recognises a chat. */
private object UnimplementedGenericParser : ChatParser {
    override val packages: Set<String> = emptySet()

    override fun parse(snapshot: ScreenSnapshot): ParsedChat? = null
}
