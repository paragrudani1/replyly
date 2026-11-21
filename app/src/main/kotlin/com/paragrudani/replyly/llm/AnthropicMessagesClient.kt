package com.paragrudani.replyly.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * One native Anthropic Messages call: `POST {baseUrl}/messages`, non-streaming, with `x-api-key`
 * and `anthropic-version`. The system prompt goes in the top-level `system` field and
 * `max_tokens` is always set.
 *
 * Reliable JSON: with [CompletionRequest.jsonMode] the request carries
 * `output_config.format` = a JSON schema of `{suggestions:[{intent,text}]}` (structured outputs;
 * no assistant prefill, which current models reject). On api.anthropic.com it also sets
 * `output_config.effort: "low"` to keep thinking short (the 1-2 s target). A provider that rejects
 * `output_config` gets [CallOutcome.Failed.rejectedJsonMode], and the generator retries once
 * without it; the prompt still asks for JSON only and the parser tolerates prose around it.
 *
 * `temperature` is never sent: API version 2026-10-21 rejects sampling parameters.
 */
internal class AnthropicMessagesClient(
    private val http: OkHttpClient,
) : LlmClient {
    override suspend fun complete(request: CompletionRequest): CallOutcome {
        val body =
            buildJsonObject {
                put("model", request.model)
                put("max_tokens", request.maxTokens)
                put("system", request.prompt.system)
                putJsonArray("messages") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", request.prompt.user)
                        },
                    )
                }
                if (request.jsonMode) {
                    putJsonObject("output_config") {
                        put("format", SUGGESTIONS_FORMAT)
                        if (isAnthropic(request.url)) put("effort", "low")
                    }
                }
            }
        val headers =
            mapOf(
                "x-api-key" to request.apiKey,
                "anthropic-version" to ANTHROPIC_VERSION,
            )
        return when (val reply = LlmHttp.post(http, request.url, headers, request.extraHeaders, body.toString())) {
            is HttpReply.Failed -> {
                CallOutcome.Failed(reply.failure)
            }

            is HttpReply.Body -> {
                if (reply.code in 200..299) {
                    parseSuccess(reply.text)
                } else {
                    httpFailure(reply.code, reply.text, request.jsonMode)
                }
            }
        }
    }

    private fun parseSuccess(text: String): CallOutcome {
        val root =
            runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: return CallOutcome.Failed(SuggestionResult.Failure.BadOutput("reply was not JSON"))
        if (root.string("type") == "error") {
            val code = errorTypeCode((root["error"] as? JsonObject)?.string("type"))
            return CallOutcome.Failed(SuggestionResult.Failure.Http(code, LlmHttp.safeDetail(code)))
        }
        val usage = root["usage"] as? JsonObject
        val tokens =
            TokenUsage(
                // Cached input is still input: count it toward the monthly total.
                promptTokens =
                    usage.int("input_tokens") + usage.int("cache_creation_input_tokens") +
                        usage.int("cache_read_input_tokens"),
                completionTokens = usage.int("output_tokens"),
            )
        // Thinking blocks (if any) are skipped; only text blocks carry the answer.
        val content =
            (root["content"] as? JsonArray)
                ?.mapNotNull { block ->
                    (block as? JsonObject)?.takeIf { it.string("type") == "text" }?.string("text")
                }?.joinToString("")
                ?.takeIf { it.isNotEmpty() }
        val finishReason =
            when (val stop = root.string("stop_reason")) {
                "max_tokens", "model_context_window_exceeded" -> FINISH_LENGTH
                else -> stop
            }
        return CallOutcome.Ok(content, tokens, root.string("model"), finishReason)
    }

    private fun httpFailure(
        code: Int,
        text: String,
        jsonMode: Boolean,
    ): CallOutcome {
        val lower = text.lowercase()
        val rejectedJsonMode =
            jsonMode && (code == 400 || code == 422) && JSON_MODE_MARKERS.any { it in lower }
        return CallOutcome.Failed(SuggestionResult.Failure.Http(code, LlmHttp.safeDetail(code)), rejectedJsonMode)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject?.int(key: String): Int = (this?.get(key) as? JsonPrimitive)?.intOrNull ?: 0

    companion object {
        /** Current Messages API version (checked on Anthropic's versioning page, 2026-11-21). */
        const val ANTHROPIC_VERSION = "2026-10-21"
        const val FINISH_LENGTH = "length"
        private val json = Json { ignoreUnknownKeys = true }

        /** Words in a 400/422 body that mean `output_config` (format or effort) was not accepted. */
        private val JSON_MODE_MARKERS = listOf("output_config", "json_schema", "effort", "structured output")

        fun isAnthropic(url: HttpUrl) = url.host == "api.anthropic.com"

        /** Error object types inside a 200 body, mapped to the status they normally come with. */
        private fun errorTypeCode(type: String?): Int =
            when (type) {
                "overloaded_error" -> 529
                "rate_limit_error" -> 429
                "api_error" -> 500
                else -> 502
            }

        /** `{"suggestions":[{"intent":"...","text":"..."}]}` as a structured-output schema. */
        internal val SUGGESTIONS_FORMAT: JsonObject =
            buildJsonObject {
                put("type", "json_schema")
                putJsonObject("schema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("suggestions") {
                            put("type", "array")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("intent") { put("type", "string") }
                                    putJsonObject("text") { put("type", "string") }
                                }
                                put(
                                    "required",
                                    buildJsonArray {
                                        add("intent")
                                        add("text")
                                    },
                                )
                                put("additionalProperties", false)
                            }
                        }
                    }
                    put("required", buildJsonArray { add("suggestions") })
                    put("additionalProperties", false)
                }
            }
    }
}
