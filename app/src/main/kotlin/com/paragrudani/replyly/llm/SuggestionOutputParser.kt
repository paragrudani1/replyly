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
 */
object SuggestionOutputParser {
    /** The prompt asks for at most ~60 words; this only bounds what the feedback log stores. */
    const val MAX_ANALYSIS_CHARS = 1_000

    @OptIn(ExperimentalSerializationApi::class)
    private val json =
        Json {
            isLenient = true
            allowTrailingComma = true
        }

    fun parse(
        raw: String,
        expected: Int = 3,
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
        return if (valid.size == expected) {
            ParsedSuggestions.Ok(
                valid,
                root
                    .string("analysis")
                    ?.trim()
                    ?.take(MAX_ANALYSIS_CHARS)
                    .orEmpty(),
            )
        } else {
            ParsedSuggestions.Invalid("${valid.size} valid suggestions of ${items.size}, expected $expected")
        }
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
