package com.paragrudani.replyly.llm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface ParsedSuggestions {
    data class Ok(
        val suggestions: List<Suggestion>,
        /** The model's short reasoning; empty when it is missing or not a string (tolerated). */
        val analysis: String = "",
        /** X only: cards dropped because no cut fit the length limit sensibly. */
        val droppedOverLimit: Int = 0,
    ) : ParsedSuggestions

    /** [reason] is fixed wording plus counts: it never quotes the model output (chat text). */
    data class Invalid(
        val reason: String,
    ) : ParsedSuggestions
}

/**
 * Reads the model's `{"analysis":"...","suggestions":[{"intent","text"}]}` reply (prompt v2).
 * Tolerates code fences and prose around the JSON, and a missing `analysis` (read as empty, capped
 * at [MAX_ANALYSIS_CHARS]). Skips items with a blank text, a missing intent or a repeated intent,
 * then keeps the first [expected] valid ones; fewer than [expected] is [ParsedSuggestions.Invalid].
 *
 * **X post length** (issue #30, `lengthLimit` set): after that check, each card over the limit
 * ([XLength.weighted]) is cut with [XLength.trim] (last sentence that fits, else last word, never
 * mid-word or mid-grapheme) and marked [Suggestion.trimmed]. A card whose cut is empty or shorter
 * than [MIN_TRIMMED_LENGTH] is dropped: the panel shows the others. All dropped is
 * [ParsedSuggestions.Invalid]. There is no second LLM call (invariant 4: one call per tap).
 *
 * **Hashtags and @-mentions** (issue #46, `xTags` set, X only): before the length check, each card
 * loses the kinds [XTags.Keep] does not keep ([XTags.strip]: leading and trailing runs go, inline
 * ones lose the sigil). A card left empty is dropped; it is not counted in `droppedOverLimit`.
 */
object SuggestionOutputParser {
    /** The prompt asks for at most ~60 words; this only bounds what the feedback log stores. */
    const val MAX_ANALYSIS_CHARS = 1_000

    /** A trimmed X card shorter than this (weighted) no longer makes sense on its own: dropped. */
    const val MIN_TRIMMED_LENGTH = 20

    @OptIn(ExperimentalSerializationApi::class)
    private val json =
        Json {
            isLenient = true
            allowTrailingComma = true
        }

    fun parse(
        raw: String,
        expected: Int = 3,
        /** X only: the weighted post length; `null` for chats (no limit). */
        lengthLimit: Int? = null,
        /** X only: which of hashtags and @-mentions stay ([XTags.keep]); `null` for chats (untouched). */
        xTags: XTags.Keep? = null,
    ): ParsedSuggestions {
        val root = findSuggestionsObject(raw) ?: return ParsedSuggestions.Invalid("no suggestions JSON in reply")
        val items = root["suggestions"] as JsonArray
        val valid = mutableListOf<Suggestion>()
        val seenIntents = mutableSetOf<String>()
        for (item in items) {
            if (valid.size == expected) break
            val obj = item as? JsonObject ?: continue
            val intent = obj.string("intent")?.trim()?.lowercase()
            val text = obj.string("text")?.trim()
            if (intent.isNullOrEmpty() || text.isNullOrEmpty() || !seenIntents.add(intent)) continue
            valid += Suggestion(intent, text)
        }
        if (valid.size != expected) {
            return ParsedSuggestions.Invalid("${valid.size} valid suggestions of ${items.size}, expected $expected")
        }
        val stripped =
            if (xTags == null) {
                valid
            } else {
                valid.mapNotNull { card ->
                    XTags.strip(card.text, xTags).ifEmpty { null }?.let { card.copy(text = it) }
                }
            }
        val kept = if (lengthLimit == null) stripped else stripped.mapNotNull { it.fitTo(lengthLimit) }
        if (kept.isEmpty()) {
            if (stripped.size < valid.size) return ParsedSuggestions.Invalid("no suggestion left after the X checks")
            return ParsedSuggestions.Invalid("all $expected suggestions over the length limit of $lengthLimit")
        }
        return ParsedSuggestions.Ok(
            kept,
            root
                .string("analysis")
                ?.trim()
                ?.take(MAX_ANALYSIS_CHARS)
                .orEmpty(),
            droppedOverLimit = stripped.size - kept.size,
        )
    }

    /** Within [limit]: unchanged. Over: trimmed and marked, or null (dropped) when too little is left. */
    private fun Suggestion.fitTo(limit: Int): Suggestion? {
        val cut = XLength.trim(text, limit)
        if (!cut.trimmed) return this
        if (XLength.weighted(cut.text) < MIN_TRIMMED_LENGTH) return null
        return copy(text = cut.text, trimmed = true)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Tries every balanced `{...}` in [raw], left to right, and returns the first with a suggestions array. */
    private fun findSuggestionsObject(raw: String): JsonObject? {
        var start = raw.indexOf('{')
        while (start >= 0) {
            val end = matchingBrace(raw, start)
            if (end > start) {
                val element = runCatching { json.parseToJsonElement(raw.substring(start, end + 1)) }.getOrNull()
                if (element.hasSuggestionsArray()) return element as JsonObject
            }
            start = raw.indexOf('{', start + 1)
        }
        return null
    }

    private fun JsonElement?.hasSuggestionsArray(): Boolean = this is JsonObject && this["suggestions"] is JsonArray

    /** Index of the `}` closing the `{` at [open], skipping braces inside strings; -1 if unbalanced. */
    private fun matchingBrace(
        raw: String,
        open: Int,
    ): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in open until raw.length) {
            val c = raw[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> if (--depth == 0) return i
            }
        }
        return -1
    }
}
