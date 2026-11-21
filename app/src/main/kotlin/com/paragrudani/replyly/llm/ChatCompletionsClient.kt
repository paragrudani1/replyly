package com.paragrudani.replyly.llm

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException
import kotlin.coroutines.resumeWithException

/** Result of one chat-completions call. Error bodies stay inside: they may quote the prompt. */
internal sealed interface CallOutcome {
    data class Ok(
        /** `null` when the reply had no text content. */
        val content: String?,
        val usage: TokenUsage,
        val model: String?,
    ) : CallOutcome

    data class Failed(
        val failure: SuggestionResult.Failure,
        /** True when the provider said it does not support `response_format`. */
        val rejectedJsonMode: Boolean = false,
    ) : CallOutcome
}

/** One OpenAI-compatible `POST {endpoint}/chat/completions`, non-streaming. */
internal class ChatCompletionsClient(
    private val http: OkHttpClient,
) {
    suspend fun complete(
        url: HttpUrl,
        apiKey: String,
        model: String,
        prompt: ChatPrompt,
        maxTokens: Int,
        jsonMode: Boolean,
    ): CallOutcome {
        val body =
            buildJsonObject {
                put("model", model)
                put(
                    "messages",
                    buildJsonArray {
                        add(message("system", prompt.system))
                        add(message("user", prompt.user))
                    },
                )
                put("max_tokens", maxTokens)
                if (jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
            }
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("HTTP-Referer", OpenAiReplyGenerator.APP_REFERER)
                .header("X-Title", OpenAiReplyGenerator.APP_TITLE)
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        val (code, text) =
            try {
                http.newCall(request).await()
            } catch (e: InterruptedIOException) {
                // OkHttp's call/read/connect timeouts. Coroutine cancellation is not an IOException.
                return CallOutcome.Failed(SuggestionResult.Failure.Timeout)
            } catch (e: IOException) {
                return CallOutcome.Failed(SuggestionResult.Failure.Network(networkMessage(e)))
            }
        return if (code in 200..299) parseSuccess(text) else httpFailure(code, text, jsonMode)
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
            return CallOutcome.Failed(SuggestionResult.Failure.Http(code, safeDetail(code)))
        }
        val usage = root["usage"] as? JsonObject
        val tokens =
            TokenUsage(
                promptTokens = usage.int("prompt_tokens"),
                completionTokens = usage.int("completion_tokens"),
            )
        val message = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
        val model = (root["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return CallOutcome.Ok(content = contentText(message?.get("content")), usage = tokens, model = model)
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
        return CallOutcome.Failed(SuggestionResult.Failure.Http(code, safeDetail(code)), rejectedJsonMode)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Fixed wording per status. The provider's own error text is never shown: it can quote
         * the prompt (chat text).
         */
        fun safeDetail(code: Int): String? =
            when {
                code == 400 -> "Request rejected. Check the model id in Settings."
                code == 401 -> "API key rejected. Check the key in Settings."
                code == 402 -> "Out of credits on the API account."
                code == 403 -> "Access denied for this key or model."
                code == 404 -> "Model or endpoint not found. Check Settings."
                code == 408 -> "The provider timed out. Try again."
                code == 413 -> "Prompt too large for this model."
                code == 429 -> "Rate limited. Wait a moment and try again."
                code in 500..599 -> "The provider had an error. Try again."
                else -> null
            }

        /** Exception class only; messages can contain hosts or addresses. */
        fun networkMessage(e: IOException): String =
            when (e) {
                is UnknownHostException -> "No internet connection, or the endpoint host is unknown."
                is ConnectException -> "Could not connect to the endpoint."
                is SSLException -> "Secure connection to the endpoint failed."
                is UnknownServiceException -> "The endpoint must use https."
                else -> "Network error (${e.javaClass.simpleName})."
            }
    }
}

/** Runs the call on OkHttp's dispatcher and reads the body there; cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Pair<Int, String> =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    val result = runCatching { response.use { it.code to it.body.string() } }
                    result.fold(
                        onSuccess = { continuation.resumeWith(Result.success(it)) },
                        onFailure = { continuation.resumeWithException(it) },
                    )
                }
            },
        )
    }
