package com.paragrudani.replyly.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * One OpenAI-compatible `POST {baseUrl}/chat/completions`, non-streaming, `Authorization: Bearer`.
 * OpenRouter-only extras (`reasoning: {effort: low}` and the attribution headers) go only to an
 * openrouter.ai host.
 */
internal class ChatCompletionsClient(
    private val http: OkHttpClient,
) : LlmClient {
    override suspend fun complete(request: CompletionRequest): CallOutcome {
        val openRouter = isOpenRouter(request.url)
        val body =
            buildJsonObject {
                put("model", request.model)
                put(
                    "messages",
                    buildJsonArray {
                        add(message("system", request.prompt.system))
                        add(message("user", request.prompt.user))
                    },
                )
                put("max_tokens", request.maxTokens)
                request.temperature?.let { put("temperature", it) }
                if (request.jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
                // Keeps reasoning models from spending max_tokens on thinking. OpenRouter-only field.
                if (openRouter) put("reasoning", buildJsonObject { put("effort", "low") })
            }
        val headers =
            buildMap {
                put("Authorization", "Bearer ${request.apiKey}")
                if (openRouter) {
                    put("HTTP-Referer", LlmReplyGenerator.APP_REFERER)
                    put("X-Title", LlmReplyGenerator.APP_TITLE)
                }
            }
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

    private fun message(
        role: String,
        content: String,
    ) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun parseSuccess(text: String): CallOutcome {
        val root =
            runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: return CallOutcome.Failed(SuggestionResult.Failure.BadOutput("reply was not JSON"))
        // OpenRouter can answer 200 with an error object (e.g. the upstream provider failed).
        (root["error"] as? JsonObject)?.let { error ->
            val code = (error["code"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 400..599 } ?: 502
            return CallOutcome.Failed(SuggestionResult.Failure.Http(code, LlmHttp.safeDetail(code)))
        }
        val usage = root["usage"] as? JsonObject
        val tokens =
            TokenUsage(
                promptTokens = usage.int("prompt_tokens"),
                completionTokens = usage.int("completion_tokens"),
            )
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val message = choice?.get("message") as? JsonObject
        val model = (root["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val finishReason = (choice?.get("finish_reason") as? JsonPrimitive)?.takeIf { it.isString }?.content
        return CallOutcome.Ok(
            content = contentText(message?.get("content")),
            usage = tokens,
            model = model,
            finishReason = finishReason,
        )
    }

    /** `content` is a string, or (some providers) a list of `{type:"text", text}` parts. */
    private fun contentText(content: Any?): String? =
        when (content) {
            is JsonPrimitive -> {
                content.takeIf { it.isString }?.content
            }

            is JsonArray -> {
                content
                    .mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content }
                    .joinToString("")
                    .takeIf { it.isNotEmpty() }
            }

            else -> {
                null
            }
        }

    private fun JsonObject?.int(key: String): Int = (this?.get(key) as? JsonPrimitive)?.intOrNull ?: 0

    private fun httpFailure(
        code: Int,
        text: String,
        jsonMode: Boolean,
    ): CallOutcome {
        val lower = text.lowercase()
        val rejectedJsonMode =
            jsonMode && (code == 400 || code == 422) &&
                ("response_format" in lower || "json_object" in lower || "json mode" in lower)
        return CallOutcome.Failed(SuggestionResult.Failure.Http(code, LlmHttp.safeDetail(code)), rejectedJsonMode)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun isOpenRouter(url: HttpUrl) = url.host == "openrouter.ai" || url.host.endsWith(".openrouter.ai")
    }
}
