package com.paragrudani.replyly.parser

import com.paragrudani.replyly.parser.generic.GenericChatParser
import com.paragrudani.replyly.parser.telegram.TelegramParser
import com.paragrudani.replyly.parser.whatsapp.WhatsAppParser
import com.paragrudani.replyly.screen.ScreenSnapshot

// Owned by ticket #2. AppGraph only calls Parsers.registry().
object Parsers {
    fun registry(): ChatParserRegistry =
        ChatParserRegistry(
            parsers = listOf(WhatsAppParser(), TelegramParser()),
            fallback = GenericChatParser(),
        )
}

/**
 * How a parser read a screen, for the debug screen. [method] and [notes] never contain chat text
 * (only counts and route names), so they can be shared as they are.
 */
data class ParseReport(
    val chat: ParsedChat?,
    /** Route taken, for example `whatsapp:view-ids` or `telegram:geometry`. */
    val method: String,
    val notes: List<String> = emptyList(),
)

/** A parser that can say which route it took. All of Replyly's parsers are. */
interface ExplainingChatParser : ChatParser {
    fun explain(snapshot: ScreenSnapshot): ParseReport

    override fun parse(snapshot: ScreenSnapshot): ParsedChat? = explain(snapshot).chat
}

/** [ChatParserRegistry.parse], plus the route the parser took. */
fun ChatParserRegistry.explain(snapshot: ScreenSnapshot): ParseReport =
    when (val parser = parserFor(snapshot.packageName)) {
        is ExplainingChatParser -> parser.explain(snapshot)
        else -> ParseReport(parser.parse(snapshot), method = parser::class.simpleName ?: "parser")
    }
