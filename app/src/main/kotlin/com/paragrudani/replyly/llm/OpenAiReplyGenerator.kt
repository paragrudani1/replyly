package com.paragrudani.replyly.llm

import com.paragrudani.replyly.cost.CostGuard
import com.paragrudani.replyly.settings.ApiKeyStore
import com.paragrudani.replyly.settings.SettingsRepository
import com.paragrudani.replyly.settings.current
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.TimeSource

/** Connect, read and whole-call timeouts. The latency target is 1-2 s; these only catch hangs. */
data class LlmTimeouts(
    val connectMillis: Long = 10_000,
    val readMillis: Long = 20_000,
    val callMillis: Long = 30_000,
)

/**
 * [ReplyGenerator] over an OpenAI-compatible chat-completions endpoint (OpenRouter by default).
 *
 * Order: API key, endpoint, [CostGuard.tryAcquire], then the call(s), then
 * [CostGuard.recordUsage] with whatever usage came back (also on bad output). One call asks for 3
 * suggestions; with [com.paragrudani.replyly.settings.ReplylySettings.useParallelCalls] it makes 3
 * concurrent calls, one per [FixedIntent], which together count as 1 request.
 *
 * JSON mode (`response_format`) is on by default; if the provider rejects it for a model, the call
 * is retried once without it and that endpoint+model skips it for the rest of the process.
 */
class OpenAiReplyGenerator(
    private val settings: SettingsRepository,
    private val apiKeys: ApiKeyStore,
    private val costGuard: CostGuard,
    http: OkHttpClient = defaultHttpClient(),
    private val promptLimits: PromptLimits = PromptLimits(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ReplyGenerator {
    private val client = ChatCompletionsClient(http)
    private val noJsonMode: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun generate(request: SuggestionRequest): SuggestionResult {
        val apiKey = apiKeys.get()?.takeIf { it.isNotBlank() } ?: return SuggestionResult.Failure.NoApiKey
        val current = settings.current()
        val url =
            chatCompletionsUrl(current.endpoint)
                ?: return SuggestionResult.Failure.Network("Endpoint URL is not valid. Check Settings.")
        if (!costGuard.tryAcquire()) {
            return SuggestionResult.Failure.LimitReached(costGuard.status.value.dailyRequestLimit)
        }
        val call = Call(url, apiKey, current.model)
        val started = timeSource.markNow()
        val tasks =
            if (current.useParallelCalls) {
                FixedIntent.entries.map { PromptTask.Single(it) }
            } else {
                listOf(PromptTask.ThreeSuggestions)
            }
        val prompts = tasks.map { PromptBuilder.build(request, it, promptLimits) }
        val outcomes =
            coroutineScope {
                tasks.zip(prompts).map { (task, prompt) -> async { call.run(prompt, maxTokens(task)) } }.awaitAll()
            }
        val latency = started.elapsedNow()

        val usage = outcomes.mapNotNull { (it as? CallOutcome.Ok)?.usage }.fold(TokenUsage(0, 0), ::plus)
        if (usage.totalTokens > 0) costGuard.recordUsage(usage)

        val suggestions = mutableListOf<Suggestion>()
        for ((task, outcome) in tasks.zip(outcomes)) {
            when (val parsed = parse(task, outcome)) {
                is Parsed.Fail -> return parsed.failure
                is Parsed.Ok -> suggestions += parsed.suggestions
            }
        }
        val promptSent =
            PromptBuilder.SYSTEM_PROMPT + "\n\n" + prompts.joinToString(PARALLEL_PROMPT_SEPARATOR) { it.user }
        val model = outcomes.firstNotNullOfOrNull { (it as? CallOutcome.Ok)?.model } ?: current.model
        return SuggestionResult.Success(suggestions, usage, latency, model, promptSent)
    }

    private sealed interface Parsed {
        data class Ok(
            val suggestions: List<Suggestion>,
        ) : Parsed

        data class Fail(
            val failure: SuggestionResult.Failure,
        ) : Parsed
    }

    private fun parse(
        task: PromptTask,
        outcome: CallOutcome,
    ): Parsed {
        val content =
            when (outcome) {
                is CallOutcome.Failed -> {
                    return Parsed.Fail(outcome.failure)
                }

                is CallOutcome.Ok -> {
                    outcome.content?.takeIf { it.isNotBlank() }
                        ?: return Parsed.Fail(SuggestionResult.Failure.BadOutput("empty reply"))
                }
            }
        val expected = if (task is PromptTask.Single) 1 else 3
        return when (val parsed = SuggestionOutputParser.parse(content, expected)) {
            is ParsedSuggestions.Invalid -> {
                Parsed.Fail(SuggestionResult.Failure.BadOutput(parsed.reason))
            }

            is ParsedSuggestions.Ok -> {
                Parsed.Ok(
                    if (task is PromptTask.Single) {
                        parsed.suggestions.map { it.copy(intent = task.intent.id) }
                    } else {
                        parsed.suggestions
                    },
                )
            }
        }
    }

    private inner class Call(
        val url: HttpUrl,
        val apiKey: String,
        val model: String,
    ) {
        private val jsonModeKey = "$url|$model"

        suspend fun run(
            prompt: ChatPrompt,
            maxTokens: Int,
        ): CallOutcome {
            val useJson = jsonModeKey !in noJsonMode
            val first = client.complete(url, apiKey, model, prompt, maxTokens, jsonMode = useJson)
            if (first is CallOutcome.Failed && first.rejectedJsonMode) {
                noJsonMode += jsonModeKey
                return client.complete(url, apiKey, model, prompt, maxTokens, jsonMode = false)
            }
            return first
        }
    }

    companion object {
        /** OpenRouter attribution headers. */
        const val APP_REFERER = "https://github.com/paragrudani1/replyly"
        const val APP_TITLE = "Replyly"

        /** Generous for short chat replies, and leaves room for models that reason before answering. */
        private const val MAX_TOKENS_THREE = 1_024
        private const val MAX_TOKENS_SINGLE = 512
        private const val PARALLEL_PROMPT_SEPARATOR = "\n\n---\n\n"

        private fun maxTokens(task: PromptTask) = if (task is PromptTask.Single) MAX_TOKENS_SINGLE else MAX_TOKENS_THREE

        fun defaultHttpClient(timeouts: LlmTimeouts = LlmTimeouts()): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(timeouts.connectMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeouts.readMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(timeouts.connectMillis, TimeUnit.MILLISECONDS)
                .callTimeout(timeouts.callMillis, TimeUnit.MILLISECONDS)
                .build()

        /** `https://openrouter.ai/api/v1` → `https://openrouter.ai/api/v1/chat/completions`. */
        internal fun chatCompletionsUrl(endpoint: String): HttpUrl? =
            "${endpoint.trim().trimEnd('/')}/chat/completions".toHttpUrlOrNull()

        private fun plus(
            a: TokenUsage,
            b: TokenUsage,
        ) = TokenUsage(a.promptTokens + b.promptTokens, a.completionTokens + b.completionTokens)
    }
}
