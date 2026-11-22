package com.paragrudani.replyly.llm

import com.paragrudani.replyly.cost.CostGuard
import com.paragrudani.replyly.settings.ApiFormat
import com.paragrudani.replyly.settings.ProfileRepository
import com.paragrudani.replyly.settings.ProviderConfig
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Connect, read and whole-call timeouts. The latency target is 1-2 s; these only catch hangs. */
data class LlmTimeouts(
    val connectMillis: Long = 10_000,
    val readMillis: Long = 20_000,
    val callMillis: Long = 30_000,
)

/** Outcome of [ConnectionTester.testConnection]. */
sealed interface ConnectionTestResult {
    data class Ok(
        val latency: Duration,
    ) : ConnectionTestResult

    data class Failed(
        val failure: SuggestionResult.Failure,
    ) : ConnectionTestResult
}

/** "Test connection" for one saved profile. */
interface ConnectionTester {
    /**
     * One tiny request through the same path as [ReplyGenerator.generate]: same key and https
     * checks, counts as 1 request against the daily limit and records its tokens.
     */
    suspend fun testConnection(profileId: String): ConnectionTestResult
}

/**
 * [ReplyGenerator] for the active provider profile, in either [ApiFormat]: OpenAI-compatible chat
 * completions ([ChatCompletionsClient]) or native Anthropic Messages ([AnthropicMessagesClient]).
 *
 * Order: active profile, API key (trimmed; visible ASCII only), endpoint (must be https: the key
 * and chat text never go over cleartext), [CostGuard.tryAcquire], then the call(s), then
 * [CostGuard.recordUsage] with whatever usage came back (also on bad output). One call per tap
 * asks for the analysis and 3 suggestions ([PromptBuilder], prompt v2).
 *
 * JSON mode (OpenAI `response_format`, Anthropic `output_config`) is on by default; if the provider
 * rejects it for a model, the call is retried once without it and that endpoint+model skips it for
 * the rest of the process.
 */
class LlmReplyGenerator(
    private val profiles: ProfileRepository,
    private val costGuard: CostGuard,
    http: OkHttpClient = defaultHttpClient(),
    private val promptLimits: PromptLimits = PromptLimits(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** Off only in tests that talk to a local cleartext MockWebServer. */
    private val requireHttps: Boolean = true,
) : ReplyGenerator,
    ConnectionTester {
    private val openAi = ChatCompletionsClient(http)
    private val anthropic = AnthropicMessagesClient(http)
    private val noJsonMode: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun generate(request: SuggestionRequest): SuggestionResult {
        val target =
            when (val prepared = prepare(profiles.resolveActive())) {
                is Prepared.Fail -> return prepared.failure
                is Prepared.Ready -> prepared.target
            }
        if (!costGuard.tryAcquire()) {
            return SuggestionResult.Failure.LimitReached(costGuard.status.value.dailyRequestLimit)
        }
        val started = timeSource.markNow()
        val prompt = PromptBuilder.build(request, promptLimits)
        val outcome = target.run(prompt, target.maxTokens ?: MAX_TOKENS)
        val latency = started.elapsedNow()

        val usage = (outcome as? CallOutcome.Ok)?.usage
        if (usage != null && usage.totalTokens > 0) costGuard.recordUsage(usage)

        val parsed =
            when (val result = parse(outcome)) {
                is Parsed.Fail -> return result.failure
                is Parsed.Ok -> result
            }
        val model = (outcome as? CallOutcome.Ok)?.model ?: target.model
        return SuggestionResult.Success(
            suggestions = parsed.suggestions,
            usage = usage ?: TokenUsage(0, 0),
            latency = latency,
            model = model,
            promptSent = prompt.fullText,
            analysis = parsed.analysis,
            promptVersion = PromptBuilder.VERSION,
        )
    }

    override suspend fun testConnection(profileId: String): ConnectionTestResult {
        val config = profiles.resolve(profileId) ?: return ConnectionTestResult.Failed(PROFILE_MISSING)
        val target =
            when (val prepared = prepare(config)) {
                is Prepared.Fail -> return ConnectionTestResult.Failed(prepared.failure)
                is Prepared.Ready -> prepared.target
            }
        if (!costGuard.tryAcquire()) {
            return ConnectionTestResult.Failed(
                SuggestionResult.Failure.LimitReached(costGuard.status.value.dailyRequestLimit),
            )
        }
        val started = timeSource.markNow()
        // No JSON mode: any reply proves the URL, key and model work; a cut-off reply is fine too.
        val outcome = target.client.complete(target.request(TEST_PROMPT, TEST_MAX_TOKENS, jsonMode = false))
        val latency = started.elapsedNow()
        return when (outcome) {
            is CallOutcome.Failed -> {
                ConnectionTestResult.Failed(outcome.failure)
            }

            is CallOutcome.Ok -> {
                if (outcome.usage.totalTokens > 0) costGuard.recordUsage(outcome.usage)
                ConnectionTestResult.Ok(latency)
            }
        }
    }

    private sealed interface Prepared {
        data class Ready(
            val target: Target,
        ) : Prepared

        data class Fail(
            val failure: SuggestionResult.Failure,
        ) : Prepared
    }

    /** Key and endpoint checks, before any cost check or request. */
    private fun prepare(config: ProviderConfig?): Prepared {
        if (config == null) return Prepared.Fail(SuggestionResult.Failure.NoApiKey)
        val apiKey =
            config.apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return Prepared.Fail(SuggestionResult.Failure.NoApiKey)
        // A pasted key can carry a zero-width space or a non-breaking space; OkHttp would reject the header.
        if (!apiKey.all { it in VISIBLE_ASCII }) return Prepared.Fail(SuggestionResult.Failure.InvalidApiKey)
        val profile = config.profile
        val url =
            ProviderUrls.endpoint(profile.format, profile.baseUrl)
                ?: return Prepared.Fail(SuggestionResult.Failure.Network("Endpoint URL is not valid. Check Settings."))
        if (requireHttps && !url.isHttps) return Prepared.Fail(SuggestionResult.Failure.InsecureEndpoint)
        val client =
            when (profile.format) {
                ApiFormat.OpenAi -> openAi
                ApiFormat.Anthropic -> anthropic
            }
        val temperature = profile.temperature.takeIf { profile.format == ApiFormat.OpenAi }
        return Prepared.Ready(
            Target(client, url, apiKey, profile.model.trim(), profile.maxTokens, temperature, config.extraHeaders),
        )
    }

    private sealed interface Parsed {
        data class Ok(
            val suggestions: List<Suggestion>,
            val analysis: String,
        ) : Parsed

        data class Fail(
            val failure: SuggestionResult.Failure,
        ) : Parsed
    }

    private fun parse(outcome: CallOutcome): Parsed {
        val content =
            when (outcome) {
                is CallOutcome.Failed -> {
                    return Parsed.Fail(outcome.failure)
                }

                is CallOutcome.Ok -> {
                    outcome.content?.takeIf { it.isNotBlank() }
                        ?: return Parsed.Fail(badOutput("empty reply", outcome))
                }
            }
        return when (val parsed = SuggestionOutputParser.parse(content)) {
            is ParsedSuggestions.Invalid -> Parsed.Fail(badOutput(parsed.reason, outcome))
            is ParsedSuggestions.Ok -> Parsed.Ok(parsed.suggestions, parsed.analysis)
        }
    }

    /** A reply that stopped at the token limit (often spent on reasoning), or a refusal, gets a clearer reason. */
    private fun badOutput(
        reason: String,
        outcome: CallOutcome.Ok,
    ) = when (outcome.finishReason) {
        AnthropicMessagesClient.FINISH_LENGTH -> SuggestionResult.Failure.BadOutput(CUT_OFF, cutOff = true)
        "refusal" -> SuggestionResult.Failure.BadOutput(REFUSED)
        else -> SuggestionResult.Failure.BadOutput(reason)
    }

    /** One resolved profile, ready to call. [toString] hides the key. */
    private inner class Target(
        val client: LlmClient,
        val url: HttpUrl,
        val apiKey: String,
        val model: String,
        val maxTokens: Int?,
        val temperature: Double?,
        val extraHeaders: Map<String, String>,
    ) {
        private val jsonModeKey = "$url|$model"

        fun request(
            prompt: ChatPrompt,
            maxTokens: Int,
            jsonMode: Boolean,
        ) = CompletionRequest(url, apiKey, model, prompt, maxTokens, jsonMode, temperature, extraHeaders)

        suspend fun run(
            prompt: ChatPrompt,
            maxTokens: Int,
        ): CallOutcome {
            val useJson = jsonModeKey !in noJsonMode
            val first = client.complete(request(prompt, maxTokens, jsonMode = useJson))
            if (first is CallOutcome.Failed && first.rejectedJsonMode) {
                noJsonMode += jsonModeKey
                return client.complete(request(prompt, maxTokens, jsonMode = false))
            }
            return first
        }

        override fun toString(): String = "Target(url=$url, model=$model)"
    }

    companion object {
        /** OpenRouter attribution headers (sent to openrouter.ai only). */
        const val APP_REFERER = "https://github.com/paragrudani1/replyly"
        const val APP_TITLE = "Replyly"

        /**
         * Default output budget when the profile sets none. Prompt v2 adds the analysis (~60 words,
         * ~100 tokens) before the 3 replies (~150 tokens), and models that think before answering
         * spend part of it on thinking: 1,536 leaves room for both.
         */
        const val MAX_TOKENS = 1_536

        /** Test connection: enough for "OK" and small enough to cost nothing. */
        internal const val TEST_MAX_TOKENS = 16
        internal val TEST_PROMPT = ChatPrompt(system = "Reply with the single word OK.", user = "Connection test.")

        private const val CUT_OFF = "reply cut off at the token limit"
        private const val REFUSED = "the model declined to answer"
        private val VISIBLE_ASCII = '!'..'~'
        private val PROFILE_MISSING = SuggestionResult.Failure.Network("This profile no longer exists.")

        fun defaultHttpClient(timeouts: LlmTimeouts = LlmTimeouts()): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(timeouts.connectMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeouts.readMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(timeouts.connectMillis, TimeUnit.MILLISECONDS)
                .callTimeout(timeouts.callMillis, TimeUnit.MILLISECONDS)
                .build()
    }
}
