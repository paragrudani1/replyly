package com.paragrudani.replyly.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
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

/** Result of one LLM call, either format. Error bodies stay inside: they may quote the prompt. */
internal sealed interface CallOutcome {
    data class Ok(
        /** `null` when the reply had no text content. */
        val content: String?,
        val usage: TokenUsage,
        val model: String?,
        /**
         * Normalised stop reason: `"length"` when the reply hit the token limit (OpenAI
         * `finish_reason: length`, Anthropic `stop_reason: max_tokens`), `"refusal"` when the model
         * declined, else the provider's value. `null` if absent.
         */
        val finishReason: String? = null,
    ) : CallOutcome

    data class Failed(
        val failure: SuggestionResult.Failure,
        /** True when the provider rejected the JSON-mode / structured-output fields. */
        val rejectedJsonMode: Boolean = false,
    ) : CallOutcome
}

/** One call to make. [toString] hides the key and header values. */
internal class CompletionRequest(
    val url: HttpUrl,
    val apiKey: String,
    val model: String,
    val prompt: ChatPrompt,
    val maxTokens: Int,
    /** OpenAI `response_format`, or Anthropic `output_config` (schema + effort). */
    val jsonMode: Boolean,
    /** Sent by the OpenAI format only; null means not sent. */
    val temperature: Double? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    override fun toString(): String =
        "CompletionRequest(url=$url, model=$model, maxTokens=$maxTokens, jsonMode=$jsonMode, " +
            "headers=${extraHeaders.keys})"
}

/** One wire format. */
internal interface LlmClient {
    suspend fun complete(request: CompletionRequest): CallOutcome
}

/** What came back over HTTP, or the failure that stopped the call. */
internal sealed interface HttpReply {
    data class Body(
        val code: Int,
        val text: String,
    ) : HttpReply

    data class Failed(
        val failure: SuggestionResult.Failure,
    ) : HttpReply
}

internal object LlmHttp {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** Extra headers may not replace these: they carry the key or frame the body. */
    val RESERVED_HEADERS = setOf("authorization", "x-api-key", "content-type", "content-length", "host")

    /**
     * POSTs [body] with [headers], then [extraHeaders] (which may override non-reserved ones such
     * as `anthropic-version`). Exceptions become fixed-wording failures; exception messages are
     * never used because they can hold the key or a host.
     */
    suspend fun post(
        http: OkHttpClient,
        url: HttpUrl,
        headers: Map<String, String>,
        extraHeaders: Map<String, String>,
        body: String,
    ): HttpReply =
        try {
            val builder = Request.Builder().url(url)
            headers.forEach { (name, value) -> builder.header(name, value) }
            extraHeaders.forEach { (name, value) ->
                if (name.lowercase() !in RESERVED_HEADERS) builder.header(name, value)
            }
            val (code, text) = http.newCall(builder.post(body.toRequestBody(JSON_MEDIA_TYPE)).build()).await()
            HttpReply.Body(code, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedIOException) {
            // OkHttp's call/read/connect timeouts. Coroutine cancellation is not an IOException.
            HttpReply.Failed(SuggestionResult.Failure.Timeout)
        } catch (e: IOException) {
            HttpReply.Failed(SuggestionResult.Failure.Network(networkMessage(e)))
        } catch (e: Exception) {
            // e.g. IllegalArgumentException from a header value. Class name only: the message may hold the key.
            HttpReply.Failed(
                SuggestionResult.Failure.Network("Could not send the request (${e.javaClass.simpleName})."),
            )
        }

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
            code == 529 -> "The provider is overloaded. Try again."
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
