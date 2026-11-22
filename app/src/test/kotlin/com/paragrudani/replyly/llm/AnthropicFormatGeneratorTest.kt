package com.paragrudani.replyly.llm

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.cost.InMemoryCostGuard
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.settings.ApiFormat
import com.paragrudani.replyly.settings.InMemoryProfileRepository
import com.paragrudani.replyly.settings.InMemoryProfileSecretStore
import com.paragrudani.replyly.settings.ProviderProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/** [LlmReplyGenerator] with an Anthropic-format profile, against a mock Messages API. */
class AnthropicFormatGeneratorTest {
    private val server = MockWebServer()
    private val secrets = InMemoryProfileSecretStore()
    private val costGuard = InMemoryCostGuard(dailyRequestLimit = 50)
    private lateinit var profiles: InMemoryProfileRepository

    private val request =
        SuggestionRequest(
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            chatTitle = "Ravi",
            isGroup = false,
            history = listOf(PromptMessage(ChatMessage("Ravi", "Kal cricket?", "6:40 pm", false))),
            aboutMe = "",
            contactNote = null,
            styleExamples = emptyList(),
            instruction = null,
            now = ZonedDateTime.of(2026, 11, 22, 19, 5, 0, 0, ZoneId.of("Asia/Kolkata")),
        )

    private val threeJson =
        """{"analysis":"Ravi asks about cricket kal = Mon 23 Nov.",""" +
            """"suggestions":[{"intent":"agree","text":"Haan pakka"},{"intent":"decline","text":"Kal nahi yaar"},""" +
            """{"intent":"ask","text":"Kitne baje?"}]}"""

    private val threeWithoutAnalysis =
        """{"suggestions":[{"intent":"agree","text":"Haan pakka"},{"intent":"decline","text":"Kal nahi yaar"},""" +
            """{"intent":"ask","text":"Kitne baje?"}]}"""

    @Before
    fun setUp() {
        server.start()
        // No /v1 in the base URL: the client adds it.
        profiles =
            InMemoryProfileRepository(
                listOf(
                    ProviderProfile(PID, "Anthropic", ApiFormat.Anthropic, server.url("/").toString(), MODEL),
                ),
                secrets = secrets,
            )
        runBlocking { secrets.setApiKey(PID, "sk-ant-test-key") }
    }

    @After
    fun tearDown() = server.close()

    private fun updateProfile(transform: (ProviderProfile) -> ProviderProfile) =
        runBlocking {
            profiles.upsert(
                transform(
                    profiles.state.value.profiles
                        .single(),
                ),
            )
        }

    private fun generator(
        timeouts: LlmTimeouts = LlmTimeouts(),
        http: OkHttpClient = LlmReplyGenerator.defaultHttpClient(timeouts),
    ) = LlmReplyGenerator(profiles, costGuard, http, requireHttps = false)

    private fun generate(timeouts: LlmTimeouts = LlmTimeouts()) = runBlocking { generator(timeouts).generate(request) }

    private fun message(
        text: String,
        inputTokens: Int = 120,
        outputTokens: Int = 30,
        stopReason: String = "end_turn",
        extraUsage: String = "",
    ): String =
        """{"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5",""" +
            """"content":[{"type":"thinking","thinking":""},{"type":"text","text":${JsonPrimitive(text)}}],""" +
            """"stop_reason":"$stopReason","stop_sequence":null,""" +
            """"usage":{"input_tokens":$inputTokens,"output_tokens":$outputTokens$extraUsage}}"""

    private fun ok(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun error(
        code: Int,
        type: String = "api_error",
        text: String = "Ravi said Kal cricket? sk-ant-test-key",
    ) = MockResponse
        .Builder()
        .code(code)
        .body("""{"type":"error","error":{"type":"$type","message":"$text"},"request_id":"req_1"}""")
        .build()

    private fun generateWith(response: MockResponse): SuggestionResult {
        server.enqueue(response)
        return generate()
    }

    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject

    @Test
    fun `happy path sends a native Messages request and returns 3 suggestions`() {
        server.enqueue(ok(message(threeJson)))

        val result = generate() as SuggestionResult.Success

        assertThat(result.suggestions.map { it.intent }).containsExactly("agree", "decline", "ask").inOrder()
        assertThat(result.usage).isEqualTo(TokenUsage(120, 30))
        assertThat(result.model).isEqualTo("claude-haiku-5-5")
        assertThat(result.latency).isGreaterThan(0.milliseconds)

        val sent = server.takeRequest()
        assertThat(sent.url.encodedPath).isEqualTo("/v1/messages")
        assertThat(sent.headers["x-api-key"]).isEqualTo("sk-ant-test-key")
        assertThat(sent.headers["anthropic-version"]).isEqualTo(AnthropicMessagesClient.ANTHROPIC_VERSION)
        assertThat(sent.headers["Authorization"]).isNull()
        assertThat(sent.headers["HTTP-Referer"]).isNull()
        val body = sent.json()
        assertThat(body["model"]!!.jsonPrimitive.content).isEqualTo("claude-haiku-5-5")
        assertThat(body["max_tokens"]!!.jsonPrimitive.content).isEqualTo(LlmReplyGenerator.MAX_TOKENS.toString())
        // Not api.anthropic.com: a plain string, no cache_control (another vendor may reject it).
        assertThat(body["system"]!!.jsonPrimitive.content).isEqualTo(PromptBuilder.SYSTEM_PROMPT)
        assertThat(sent.headers["anthropic-beta"]).isNull()
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertThat(messages.map { it["role"]!!.jsonPrimitive.content }).containsExactly("user")
        assertThat(messages[0]["content"]!!.jsonPrimitive.content).contains("Ravi: Kal cricket?")
        assertThat(
            messages[0]["content"]!!.jsonPrimitive.content,
        ).contains("Now: Sunday, 22 Nov 2026, 7:05 pm (evening)")
        assertThat(result.analysis).isEqualTo("Ravi asks about cricket kal = Mon 23 Nov.")
        assertThat(result.promptVersion).isEqualTo(PromptBuilder.VERSION)
        val format = body["output_config"]!!.jsonObject["format"]!!.jsonObject
        assertThat(format["type"]!!.jsonPrimitive.content).isEqualTo("json_schema")
        assertThat(format).isEqualTo(AnthropicMessagesClient.SUGGESTIONS_FORMAT)
        // Not api.anthropic.com: no effort (another vendor may not know it). Never temperature.
        assertThat(body["output_config"]!!.jsonObject.containsKey("effort")).isFalse()
        assertThat(body.containsKey("temperature")).isFalse()
        assertThat(body.containsKey("response_format")).isFalse()
        assertThat(body.containsKey("reasoning")).isFalse()
    }

    @Test
    fun `counts one request and records usage including cached input`() {
        server.enqueue(
            ok(
                message(
                    threeJson,
                    inputTokens = 100,
                    outputTokens = 40,
                    extraUsage = ""","cache_creation_input_tokens":5,"cache_read_input_tokens":7""",
                ),
            ),
        )
        generate()
        val status = costGuard.status.value
        assertThat(status.requestsToday).isEqualTo(1)
        assertThat(status.promptTokensThisMonth).isEqualTo(112)
        assertThat(status.completionTokensThisMonth).isEqualTo(40)
    }

    @Test
    fun `temperature is never sent in the Anthropic format`() {
        updateProfile { it.copy(temperature = 0.2) }
        server.enqueue(ok(message(threeJson)))
        generate()
        assertThat(server.takeRequest().json().containsKey("temperature")).isFalse()
    }

    @Test
    fun `extra headers are sent, may override anthropic-version, never x-api-key`() {
        updateProfile { it.copy(extraHeaderNames = listOf("anthropic-version", "x-api-key", "X-Proxy")) }
        runBlocking {
            secrets.setExtraHeaders(
                PID,
                mapOf("anthropic-version" to "2023-06-01", "x-api-key" to "stolen", "X-Proxy" to "p-secret"),
            )
        }
        server.enqueue(ok(message(threeJson)))
        generate()
        val sent = server.takeRequest()
        assertThat(sent.headers["anthropic-version"]).isEqualTo("2023-06-01")
        assertThat(sent.headers["x-api-key"]).isEqualTo("sk-ant-test-key")
        assertThat(sent.headers["X-Proxy"]).isEqualTo("p-secret")
    }

    @Test
    fun `max tokens from the profile is sent`() {
        updateProfile { it.copy(maxTokens = 2048) }
        server.enqueue(ok(message(threeJson)))
        generate()
        assertThat(
            server
                .takeRequest()
                .json()["max_tokens"]!!
                .jsonPrimitive.content,
        ).isEqualTo("2048")
    }

    @Test
    fun `each HTTP error maps to its code and fixed wording`() {
        val cases =
            mapOf(
                400 to "invalid_request_error",
                401 to "authentication_error",
                403 to "permission_error",
                404 to "not_found_error",
                429 to "rate_limit_error",
                500 to "api_error",
                529 to "overloaded_error",
            )
        for ((code, type) in cases) {
            val result = generateWith(error(code, type)) as SuggestionResult.Failure.Http
            assertThat(result.code).isEqualTo(code)
            assertThat(result.detail).isEqualTo(LlmHttp.safeDetail(code))
            assertThat(result.detail).doesNotContain("cricket")
            assertThat(result.detail).doesNotContain("sk-ant")
        }
        assertThat(LlmHttp.safeDetail(529)).contains("overloaded")
        // Each failed call still counts as one request (the limit guards spending, not success).
        assertThat(costGuard.status.value.requestsToday).isEqualTo(cases.size)
    }

    @Test
    fun `an error object inside a 200 reply is an HTTP failure`() {
        val result = generateWith(ok("""{"type":"error","error":{"type":"overloaded_error","message":"x"}}"""))
        assertThat((result as SuggestionResult.Failure.Http).code).isEqualTo(529)
    }

    @Test
    fun `stop_reason model_context_window_exceeded is a cut-off reply`() {
        val cut = """{"suggestions":[{"intent":"agree","text":"Haan"""
        val result = generateWith(ok(message(cut, stopReason = "model_context_window_exceeded")))
        assertThat(result).isEqualTo(SuggestionResult.Failure.BadOutput(CUT_OFF, cutOff = true))
    }

    @Test
    fun `stop_reason max_tokens is a cut-off reply`() {
        val cut = """{"suggestions":[{"intent":"agree","text":"Haan"""
        val result = generateWith(ok(message(cut, stopReason = "max_tokens")))
        assertThat(result).isEqualTo(SuggestionResult.Failure.BadOutput(CUT_OFF, cutOff = true))
    }

    @Test
    fun `a refusal is bad output with a fixed reason`() {
        val result = generateWith(ok(message("I can't help with that.", stopReason = "refusal")))
        assertThat(result).isEqualTo(SuggestionResult.Failure.BadOutput("the model declined to answer"))
    }

    @Test
    fun `malformed JSON in the reply is bad output, usage still recorded`() {
        val result = generateWith(ok(message("""{"suggestions":[{"intent":"agree","text":"Haan"}""", 90, 20)))
        assertThat(result).isInstanceOf(SuggestionResult.Failure.BadOutput::class.java)
        assertThat((result as SuggestionResult.Failure.BadOutput).cutOff).isFalse()
        assertThat(result.reason).doesNotContain("Haan")
        assertThat(costGuard.status.value.totalTokensThisMonth).isEqualTo(110)
    }

    @Test
    fun `a 200 with a non-JSON body is bad output`() {
        val result = generateWith(ok("<html>Bad gateway</html>"))
        assertThat(result).isEqualTo(SuggestionResult.Failure.BadOutput("reply was not JSON"))
    }

    @Test
    fun `a reply with no text block is bad output`() {
        val noText =
            """{"type":"message","content":[],"stop_reason":"end_turn",""" +
                """"usage":{"input_tokens":1,"output_tokens":0}}"""
        assertThat(generateWith(ok(noText))).isEqualTo(SuggestionResult.Failure.BadOutput("empty reply"))
    }

    @Test
    fun `falls back without output_config when the provider rejects it, and remembers`() {
        val rejected = "output_config: Extra inputs are not permitted"
        server.enqueue(error(400, "invalid_request_error", rejected))
        server.enqueue(ok(message(threeJson)))
        server.enqueue(ok(message(threeJson)))
        val generator = generator()

        assertThat(runBlocking { generator.generate(request) }).isInstanceOf(SuggestionResult.Success::class.java)
        assertThat(runBlocking { generator.generate(request) }).isInstanceOf(SuggestionResult.Success::class.java)

        assertThat(server.takeRequest().json().containsKey("output_config")).isTrue()
        assertThat(server.takeRequest().json().containsKey("output_config")).isFalse()
        assertThat(server.takeRequest().json().containsKey("output_config")).isFalse()
        assertThat(costGuard.status.value.requestsToday).isEqualTo(2)
    }

    @Test
    fun `other 400s do not retry`() {
        val result = generateWith(error(400, "invalid_request_error", "model: claude-x"))
        assertThat((result as SuggestionResult.Failure.Http).code).isEqualTo(400)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `slow reply is a timeout`() {
        server.enqueue(ok(message(threeJson)).newBuilder().headersDelay(2, TimeUnit.SECONDS).build())
        val result = generate(LlmTimeouts(connectMillis = 1_000, readMillis = 200, callMillis = 1_000))
        assertThat(result).isEqualTo(SuggestionResult.Failure.Timeout)
    }

    @Test
    fun `api_anthropic_com gets low effort with the schema`() {
        val bodies = mutableListOf<String>()
        val urls = mutableListOf<String>()
        val fake =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val sent = chain.request()
                    urls += sent.url.toString()
                    bodies += Buffer().also { sent.body!!.writeTo(it) }.readUtf8()
                    Response
                        .Builder()
                        .request(sent)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(message(threeJson).toResponseBody("application/json".toMediaType()))
                        .build()
                }.build()
        updateProfile { it.copy(baseUrl = "https://api.anthropic.com") }
        val result = runBlocking { LlmReplyGenerator(profiles, costGuard, fake).generate(request) }

        assertThat(result).isInstanceOf(SuggestionResult.Success::class.java)
        assertThat(urls).containsExactly("https://api.anthropic.com/v1/messages")
        val config = Json.parseToJsonElement(bodies.single()).jsonObject["output_config"]!!.jsonObject
        assertThat(config["effort"]!!.jsonPrimitive.content).isEqualTo("low")
        assertThat(config.containsKey("format")).isTrue()
    }

    @Test
    fun `api_anthropic_com caches the system prompt with cache_control and no beta header`() {
        val bodies = mutableListOf<String>()
        val headers = mutableListOf<okhttp3.Headers>()
        val fake =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val sent = chain.request()
                    headers += sent.headers
                    bodies += Buffer().also { sent.body!!.writeTo(it) }.readUtf8()
                    Response
                        .Builder()
                        .request(sent)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(message(threeJson).toResponseBody("application/json".toMediaType()))
                        .build()
                }.build()
        updateProfile { it.copy(baseUrl = "https://api.anthropic.com") }
        val result = runBlocking { LlmReplyGenerator(profiles, costGuard, fake).generate(request) }

        assertThat(result).isInstanceOf(SuggestionResult.Success::class.java)
        val system = Json.parseToJsonElement(bodies.single()).jsonObject["system"]!!.jsonArray
        assertThat(system).hasSize(1)
        val block = system.single().jsonObject
        assertThat(block["type"]!!.jsonPrimitive.content).isEqualTo("text")
        assertThat(block["text"]!!.jsonPrimitive.content).isEqualTo(PromptBuilder.SYSTEM_PROMPT)
        assertThat(block["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("ephemeral")
        // Prompt caching is GA: no beta header, and the user message (which changes) is not cached.
        assertThat(headers.single()["anthropic-beta"]).isNull()
        assertThat(bodies.single().split("cache_control")).hasSize(2)
    }

    @Test
    fun `the schema asks for analysis first, then exactly the suggestions`() {
        val schema = AnthropicMessagesClient.SUGGESTIONS_FORMAT["schema"]!!.jsonObject
        assertThat(schema["properties"]!!.jsonObject.keys).containsExactly("analysis", "suggestions").inOrder()
        assertThat(schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("analysis", "suggestions")
        assertThat(schema["additionalProperties"]!!.jsonPrimitive.content).isEqualTo("false")
    }

    @Test
    fun `a reply without analysis still succeeds, with an empty analysis`() {
        server.enqueue(ok(message(threeWithoutAnalysis)))
        val result = generate() as SuggestionResult.Success
        assertThat(result.suggestions).hasSize(3)
        assertThat(result.analysis).isEmpty()
    }

    @Test
    fun `test connection is one tiny request that counts and records tokens`() {
        server.enqueue(ok(message("OK", inputTokens = 12, outputTokens = 2)))

        val result = runBlocking { generator().testConnection(PID) }

        assertThat(result).isInstanceOf(ConnectionTestResult.Ok::class.java)
        val sent = server.takeRequest().json()
        assertThat(sent["max_tokens"]!!.jsonPrimitive.content).isEqualTo(LlmReplyGenerator.TEST_MAX_TOKENS.toString())
        assertThat(sent.containsKey("output_config")).isFalse()
        assertThat(sent["messages"].toString()).doesNotContain("cricket")
        assertThat(costGuard.status.value.requestsToday).isEqualTo(1)
        assertThat(costGuard.status.value.totalTokensThisMonth).isEqualTo(14)
    }

    @Test
    fun `test connection reports the mapped error`() {
        server.enqueue(error(401, "authentication_error"))
        val result = runBlocking { generator().testConnection(PID) } as ConnectionTestResult.Failed
        assertThat(result.failure).isEqualTo(SuggestionResult.Failure.Http(401, LlmHttp.safeDetail(401)))
        assertThat(costGuard.status.value.requestsToday).isEqualTo(1)
    }

    @Test
    fun `test connection with a cut-off reply is still OK`() {
        server.enqueue(ok(message("", stopReason = "max_tokens")))
        assertThat(runBlocking { generator().testConnection(PID) }).isInstanceOf(ConnectionTestResult.Ok::class.java)
    }

    @Test
    fun `test connection checks key, https and the limit before any request`() {
        runBlocking { secrets.setApiKey(PID, "") }
        assertThat(runBlocking { generator().testConnection(PID) })
            .isEqualTo(ConnectionTestResult.Failed(SuggestionResult.Failure.NoApiKey))

        runBlocking { secrets.setApiKey(PID, "sk-ant-test-key") }
        val strict = LlmReplyGenerator(profiles, costGuard, LlmReplyGenerator.defaultHttpClient())
        assertThat(runBlocking { strict.testConnection(PID) })
            .isEqualTo(ConnectionTestResult.Failed(SuggestionResult.Failure.InsecureEndpoint))

        val spent = InMemoryCostGuard(dailyRequestLimit = 1).also { runBlocking { it.tryAcquire() } }
        val http = LlmReplyGenerator.defaultHttpClient()
        val limited = LlmReplyGenerator(profiles, spent, http, requireHttps = false)
        assertThat(runBlocking { limited.testConnection(PID) })
            .isEqualTo(ConnectionTestResult.Failed(SuggestionResult.Failure.LimitReached(1)))

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(costGuard.status.value.requestsToday).isEqualTo(0)
    }

    @Test
    fun `switching the active profile switches the format`() {
        val openAiPath = "/openai/v1/"
        val second = ProviderProfile("p2", "Compat", ApiFormat.OpenAi, server.url(openAiPath).toString(), "m")
        runBlocking {
            profiles.upsert(second)
            secrets.setApiKey("p2", "sk-compat")
            profiles.setActive("p2")
        }
        val chatCompletion =
            """{"model":"m","choices":[{"message":{"content":${JsonPrimitive(threeJson)}},"finish_reason":"stop"}]}"""
        server.enqueue(ok(chatCompletion))
        assertThat(generate()).isInstanceOf(SuggestionResult.Success::class.java)
        val sent = server.takeRequest()
        assertThat(sent.url.encodedPath).isEqualTo("/openai/v1/chat/completions")
        assertThat(sent.headers["Authorization"]).isEqualTo("Bearer sk-compat")
        assertThat(sent.headers["x-api-key"]).isNull()
    }

    companion object {
        private const val PID = "p1"
        private const val MODEL = "claude-haiku-5-5"
        private const val CUT_OFF = "reply cut off at the token limit"
    }
}
