package com.paragrudani.replyly.parser

import com.paragrudani.replyly.screen.ScreenSnapshot

/** Reads a chat from a screen tree. One implementation per app, plus a generic fallback. */
interface ChatParser {
    /** Packages this parser handles. Empty for the generic fallback. */
    val packages: Set<String>

    /** Returns `null` when the screen is not an open chat (chat list, settings, camera...). */
    fun parse(snapshot: ScreenSnapshot): ParsedChat?
}

/** Picks the parser for a package, falling back to [fallback] for every other app. */
class ChatParserRegistry(
    parsers: List<ChatParser>,
    private val fallback: ChatParser,
) {
    private val byPackage: Map<String, ChatParser> =
        buildMap {
            for (parser in parsers) {
                for (pkg in parser.packages) {
                    check(put(pkg, parser) == null) { "Two parsers claim $pkg" }
                }
            }
        }

    fun parserFor(packageName: String): ChatParser = byPackage[packageName] ?: fallback

    fun parse(snapshot: ScreenSnapshot): ParsedChat? = parserFor(snapshot.packageName).parse(snapshot)
}
