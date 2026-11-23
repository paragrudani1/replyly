package com.paragrudani.replyly.llm

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.cost.InMemoryCostGuard
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.parser.TargetApps
import com.paragrudani.replyly.parser.x.XContext
import com.paragrudani.replyly.parser.x.XMode
import com.paragrudani.replyly.parser.x.XPost
import com.paragrudani.replyly.settings.ApiFormat
import com.paragrudani.replyly.settings.InMemoryProfileRepository
import com.paragrudani.replyly.settings.InMemoryProfileSecretStore
import com.paragrudani.replyly.settings.ProviderProfile
import com.paragrudani.replyly.settings.ReplylySettings
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

/** [LlmReplyGenerator] with an OpenAI-format profile (OpenRouter, OpenAI, compatible servers). */
class OpenAiFormatGeneratorTest {
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

    @Before
    fun setUp() {
        server.start()
        profiles =
            InMemoryProfileRepository(
                listOf(ProviderProfile(PID, "Test", ApiFormat.OpenAi, server.url("/api/v1/").toString(), "test/model")),
                secrets = secrets,
            )
        runBlocking { secrets.setApiKey(PID, "sk-or-test-key") }
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

    private fun generator(timeouts: LlmTimeouts = LlmTimeouts()) =
        LlmReplyGenerator(
            profiles,
            costGuard,
            LlmReplyGenerator.defaultHttpClient(timeouts),
            requireHttps = false, // MockWebServer speaks cleartext.
        )

    private fun generate(timeouts: LlmTimeouts = LlmTimeouts()) = runBlocking { generator(timeouts).generate(request) }

    private fun completion(
        content: String,
        promptTokens: Int = 120,
        completionTokens: Int = 30,
        model: String = "test/model-2026",
        finishReason: String = "stop",
    ): String =
        """{"id":"gen-1","model":"$model","choices":[{"index":0,"message":{"role":"assistant",""" +
            """"content":${JsonPrimitive(content)}},"finish_reason":"$finishReason"}],""" +
            """"usage":{"prompt_tokens":$promptTokens,"completion_tokens":$completionTokens,""" +
            """"total_tokens":${promptTokens + completionTokens}}}"""

    private fun ok(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun error(
        code: Int,
        body: String = """{"error":{"message":"Ravi said Kal cricket? sk-or-test-key"}}""",
    ) = MockResponse
        .Builder()
        .code(code)
        .body(body)
        .build()

    private val threeJson =
        """{"analysis":"Ravi asks about cricket kal = Mon 23 Nov.",""" +
            """"suggestions":[{"intent":"agree","text":"Haan pakka"},{"intent":"decline","text":"Kal nahi yaar"},""" +
            """{"intent":"ask","text":"Kitne baje?"}]}"""

    private val threeWithoutAnalysis =
        """{"suggestions":[{"intent":"agree","text":"Haan pakka"},{"intent":"decline","text":"Kal nahi yaar"},""" +
            """{"intent":"ask","text":"Kitne baje?"}]}"""

    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject

    @Test
    fun `happy path returns 3 suggestions and sends a well-formed request`() {
        server.enqueue(ok(completion(threeJson)))

        val result = generate() as SuggestionResult.Success

        assertThat(result.suggestions)
            .containsExactly(
                Suggestion("agree", "Haan pakka"),
                Suggestion("decline", "Kal nahi yaar"),
                Suggestion("ask", "Kitne baje?"),
            ).inOrder()
        assertThat(result.usage).isEqualTo(TokenUsage(promptTokens = 120, completionTokens = 30))
        assertThat(result.model).isEqualTo("test/model-2026")
        assertThat(result.latency).isGreaterThan(0.milliseconds)
        assertThat(result.promptSent).contains("Kal cricket?")

        val sent = server.takeRequest()
        assertThat(sent.method).isEqualTo("POST")
        assertThat(sent.url.encodedPath).isEqualTo("/api/v1/chat/completions")
        assertThat(sent.headers["Authorization"]).isEqualTo("Bearer sk-or-test-key")
        // OpenRouter attribution headers go to openrouter.ai only.
        assertThat(sent.headers["HTTP-Referer"]).isNull()
        assertThat(sent.headers["X-Title"]).isNull()
        val body = sent.json()
        assertThat(body["model"]!!.jsonPrimitive.content).isEqualTo("test/model")
        assertThat(body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("json_object")
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertThat(messages.map { it["role"]!!.jsonPrimitive.content }).containsExactly("system", "user").inOrder()
        assertThat(messages[0]["content"]!!.jsonPrimitive.content).isEqualTo(PromptBuilder.SYSTEM_PROMPT)
        assertThat(messages[1]["content"]!!.jsonPrimitive.content).contains("Ravi: Kal cricket?")
        assertThat(result.analysis).isEqualTo("Ravi asks about cricket kal = Mon 23 Nov.")
        assertThat(result.promptVersion).isEqualTo(2)
        // OpenAI format: no Anthropic caching field on any host.
        assertThat(sent.json().toString()).doesNotContain("cache_control")
        assertThat(
            result.promptSent,
        ).isEqualTo(PromptBuilder.SYSTEM_PROMPT + "\n\n" + messages[1]["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `X - one call, the X system prompt, and every card held to the post length`() {
        // The first sentence keeps over half the limit, so the cut ends there (review r1 S4).
        val first =
            "Agree on local-first: " + "pick a data model that can merge from day one, ".repeat(3).trimEnd(' ', ',') +
                "."
        val long = "$first " + "Sync later is the hard part, ".repeat(12) + "really."
        val xJson =
            """{"analysis":"a","suggestions":[{"intent":"agree","text":${JsonPrimitive(long)}},""" +
                """{"intent":"question","text":"Which part?"},{"intent":"insight","text":${JsonPrimitive(
                    "x".repeat(300),
                )}}]}"""
        server.enqueue(ok(completion(xJson)))
        val post = XPost("Meera Iyer", "meera_writes", "Most apps don't need a backend on day one.", "2h")
        val xRequest = request.copy(packageName = TargetApps.X, appLabel = "X", x = XContext(XMode.REPLY, post))

        val result = runBlocking { generator().generate(xRequest) } as SuggestionResult.Success

        assertThat(server.requestCount).isEqualTo(1)
        val messages = server.takeRequest().json()["messages"]!!.jsonArray
        assertThat(messages[0].jsonObject["content"]!!.jsonPrimitive.content).isEqualTo(XPromptBuilder.SYSTEM_PROMPT)
        assertThat(result.lengthLimit).isEqualTo(280)
        assertThat(result.droppedOverLimit).isEqualTo(1)
        assertThat(result.suggestions)
            .containsExactly(
                Suggestion("agree", first, trimmed = true),
                Suggestion("question", "Which part?"),
            ).inOrder()
    }

    @Test
    fun `chats carry no length limit`() {
        server.enqueue(ok(completion(threeJson)))
        val result = generate() as SuggestionResult.Success
        assertThat(result.lengthLimit).isNull()
        assertThat(result.droppedOverLimit).isEqualTo(0)
    }

    @Test
    fun `a reply without analysis still succeeds, with an empty analysis`() {
        server.enqueue(ok(completion(threeWithoutAnalysis)))
        val result = generate() as SuggestionResult.Success
        assertThat(result.suggestions).hasSize(3)
        assertThat(result.analysis).isEmpty()
    }

    @Test
    fun `a non-string analysis is tolerated as empty`() {
        server.enqueue(ok(completion(threeWithoutAnalysis.replaceFirst("{", """{"analysis":42,"""))))
        assertThat((generate() as SuggestionResult.Success).analysis).isEmpty()
    }

    @Test
    fun `one call per generate, with the default max tokens`() {
        server.enqueue(ok(completion(threeJson)))
        generate()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(
            server
                .takeRequest()
                .json()["max_tokens"]!!
                .jsonPrimitive.content,
        ).isEqualTo(LlmReplyGenerator.MAX_TOKENS.toString())
    }

    @Test
    fun `counts one request and records the usage`() {
        server.enqueue(ok(completion(threeJson, promptTokens = 100, completionTokens = 40)))
        generate()
        val status = costGuard.status.value
        assertThat(status.requestsToday).isEqualTo(1)
        assertThat(status.promptTokensThisMonth).isEqualTo(100)
        assertThat(status.completionTokensThisMonth).isEqualTo(40)
    }

    @Test
    fun `no key means no call and no request counted`() {
        runBlocking { secrets.setApiKey(PID, "") }
        assertThat(generate()).isEqualTo(SuggestionResult.Failure.NoApiKey)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(costGuard.status.value.requestsToday).isEqualTo(0)
    }

    @Test
    fun `a cleartext endpoint is refused before any request or cost check`() {
        val result =
            runBlocking {
                LlmReplyGenerator(
                    profiles,
                    costGuard,
                    LlmReplyGenerator.defaultHttpClient(),
                ).generate(request)
            }

        assertThat(result).isEqualTo(SuggestionResult.Failure.InsecureEndpoint)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(costGuard.status.value.requestsToday).isEqualTo(0)
    }

    @Test
    fun `daily limit reached means no call`() {
        val guard = InMemoryCostGuard(dailyRequestLimit = 1)
        runBlocking { guard.tryAcquire() }
        val result =
            runBlocking {
                LlmReplyGenerator(
                    profiles,
                    guard,
                    LlmReplyGenerator.defaultHttpClient(),
                    requireHttps = false,
                ).generate(request)
            }
        assertThat(result).isEqualTo(SuggestionResult.Failure.LimitReached(dailyLimit = 1))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `HTTP errors map to the code and a short safe message`() {
        for (code in listOf(401, 402, 429, 500, 503)) {
            server.enqueue(error(code))
            val result = generate() as SuggestionResult.Failure.Http
            assertThat(result.code).isEqualTo(code)
            assertThat(result.detail).isNotEmpty()
            assertThat(result.detail!!.length).isAtMost(80)
            assertThat(result.detail).doesNotContain("cricket")
            assertThat(result.detail).doesNotContain("sk-or")
        }
        assertThat((generateWith(error(401)) as SuggestionResult.Failure.Http).detail).contains("key")
        assertThat((generateWith(error(402)) as SuggestionResult.Failure.Http).detail).contains("credit")
        assertThat((generateWith(error(429)) as SuggestionResult.Failure.Http).detail).contains("Rate limited")
    }

    private fun generateWith(response: MockResponse): SuggestionResult {
        server.enqueue(response)
        return generate()
    }

    @Test
    fun `an error object inside a 200 reply is an HTTP failure`() {
        val result = generateWith(ok("""{"error":{"code":502,"message":"Provider returned error"}}"""))
        assertThat(result).isInstanceOf(SuggestionResult.Failure.Http::class.java)
        assertThat((result as SuggestionResult.Failure.Http).code).isEqualTo(502)
    }

    @Test
    fun `falls back without response_format when the model rejects it, and remembers`() {
        server.enqueue(error(400, """{"error":{"message":"response_format json_object is not supported"}}"""))
        server.enqueue(ok(completion(threeJson)))
        server.enqueue(ok(completion(threeJson)))
        val generator = generator()

        assertThat(runBlocking { generator.generate(request) }).isInstanceOf(SuggestionResult.Success::class.java)
        assertThat(runBlocking { generator.generate(request) }).isInstanceOf(SuggestionResult.Success::class.java)

        assertThat(server.takeRequest().json().containsKey("response_format")).isTrue()
        assertThat(server.takeRequest().json().containsKey("response_format")).isFalse()
        assertThat(server.takeRequest().json().containsKey("response_format")).isFalse()
        assertThat(costGuard.status.value.requestsToday).isEqualTo(2)
    }

    @Test
    fun `other 400s do not retry`() {
        val result = generateWith(error(400, """{"error":{"message":"test/model is not a valid model ID"}}"""))
        assertThat((result as SuggestionResult.Failure.Http).code).isEqualTo(400)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `slow reply is a timeout`() {
        server.enqueue(ok(completion(threeJson)).newBuilder().headersDelay(2, TimeUnit.SECONDS).build())
        val result = generate(LlmTimeouts(connectMillis = 1_000, readMillis = 200, callMillis = 1_000))
        assertThat(result).isEqualTo(SuggestionResult.Failure.Timeout)
    }

    @Test
    fun `unreachable endpoint is a network failure`() {
        server.close()
        val result = generate() as SuggestionResult.Failure.Network
        assertThat(result.message).isNotEmpty()
        assertThat(result.message).doesNotContain("sk-or")
    }

    @Test
    fun `invalid endpoint is a network failure without a call`() {
        updateProfile { it.copy(baseUrl = "not a url") }
        assertThat(generate()).isInstanceOf(SuggestionResult.Failure.Network::class.java)
        assertThat(costGuard.status.value.requestsToday).isEqualTo(0)
    }

    @Test
    fun `fenced JSON with prose around it still succeeds`() {
        val result = generateWith(ok(completion("Here you go:\n```json\n$threeJson\n```")))
        assertThat((result as SuggestionResult.Success).suggestions).hasSize(3)
    }

    @Test
    fun `partly invalid JSON is bad output, and its usage is still recorded`() {
        val twoValid =
            """{"suggestions":[{"intent":"agree","text":"Haan"},{"intent":"agree","text":"Ok"},""" +
                """{"intent":"ask","text":"  "},{"intent":"decline","text":"Nahi"}]}"""
        val result = generateWith(ok(completion(twoValid, promptTokens = 90, completionTokens = 20)))
        assertThat(result).isInstanceOf(SuggestionResult.Failure.BadOutput::class.java)
        assertThat((result as SuggestionResult.Failure.BadOutput).reason).doesNotContain("Haan")
        assertThat(costGuard.status.value.totalTokensThisMonth).isEqualTo(110)
    }

    @Test
    fun `missing content is bad output`() {
        val result = generateWith(ok("""{"choices":[{"message":{"role":"assistant","content":null}}]}"""))
        assertThat(result).isInstanceOf(SuggestionResult.Failure.BadOutput::class.java)
    }

    @Test
    fun `key with a trailing newline is trimmed before use`() {
        runBlocking { secrets.setApiKey(PID, "sk-or-x\n") }
        server.enqueue(ok(completion(threeJson)))
        assertThat(generate()).isInstanceOf(SuggestionResult.Success::class.java)
        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer sk-or-x")
    }

    @Test
    fun `key with invalid characters fails typed, without a call or a counted request`() {
        for (bad in listOf("sk-or-\u200bx", "sk-or x", "sk-or-é")) {
            runBlocking { secrets.setApiKey(PID, bad) }
            val result = generate()
            assertThat(result).isEqualTo(SuggestionResult.Failure.InvalidApiKey)
            assertThat(SuggestionResult.Failure.InvalidApiKey.MESSAGE).doesNotContain("sk-or")
        }
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(costGuard.status.value.requestsToday).isEqualTo(0)
    }

    @Test
    fun `content given as a list of text parts is joined`() {
        val parts =
            """{"model":"m","choices":[{"message":{"role":"assistant","content":[""" +
                """{"type":"text","text":${JsonPrimitive(threeJson.take(40))}},""" +
                """{"type":"text","text":${JsonPrimitive(threeJson.drop(40))}}]}}]}"""
        val result = generateWith(ok(parts)) as SuggestionResult.Success
        assertThat(result.suggestions.map { it.intent }).containsExactly("agree", "decline", "ask").inOrder()
    }

    @Test
    fun `a 200 with a non-JSON body is bad output`() {
        val result = generateWith(ok("<html>Bad gateway</html>"))
        assertThat(result).isEqualTo(SuggestionResult.Failure.BadOutput("reply was not JSON"))
    }

    @Test
    fun `reply cut off at the token limit says so`() {
        val cut = """{"suggestions":[{"intent":"agree","text":"Haan"""
        val result = generateWith(ok(completion(cut, finishReason = "length")))
        assertThat(
            result,
        ).isEqualTo(SuggestionResult.Failure.BadOutput("reply cut off at the token limit", cutOff = true))
        val empty = generateWith(ok(completion("", finishReason = "length")))
        assertThat(
            empty,
        ).isEqualTo(SuggestionResult.Failure.BadOutput("reply cut off at the token limit", cutOff = true))
    }

    @Test
    fun `finish_reason length with a valid reply still succeeds`() {
        val result = generateWith(ok(completion(threeJson, finishReason = "length")))
        assertThat(result).isInstanceOf(SuggestionResult.Success::class.java)
    }

    @Test
    fun `other endpoints get no reasoning field`() {
        server.enqueue(ok(completion(threeJson)))
        generate()
        assertThat(server.takeRequest().json().containsKey("reasoning")).isFalse()
    }

    @Test
    fun `openrouter gets low reasoning effort`() {
        val bodies = mutableListOf<String>()
        val hosts = mutableListOf<String>()
        val referers = mutableListOf<String?>()
        val fake =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val sent = chain.request()
                    hosts += sent.url.host
                    referers += sent.header("HTTP-Referer")
                    bodies += Buffer().also { sent.body!!.writeTo(it) }.readUtf8()
                    Response
                        .Builder()
                        .request(sent)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(completion(threeJson).toResponseBody("application/json".toMediaType()))
                        .build()
                }.build()
        updateProfile { it.copy(baseUrl = ReplylySettings.DEFAULT_ENDPOINT) }
        val result = runBlocking { LlmReplyGenerator(profiles, costGuard, fake).generate(request) }

        assertThat(result).isInstanceOf(SuggestionResult.Success::class.java)
        assertThat(hosts).containsExactly("openrouter.ai")
        val reasoning = Json.parseToJsonElement(bodies.single()).jsonObject["reasoning"]!!.jsonObject
        assertThat(reasoning["effort"]!!.jsonPrimitive.content).isEqualTo("low")
        assertThat(referers).containsExactly(LlmReplyGenerator.APP_REFERER)
    }

    /** Answers every call with [replies] in turn (the last one repeats) and records the bodies. */
    private fun fakeHttp(
        bodies: MutableList<JsonObject>,
        vararg replies: Pair<Int, String>,
    ): OkHttpClient {
        var call = 0
        return OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val sent = chain.request()
                bodies += Json.parseToJsonElement(Buffer().also { sent.body!!.writeTo(it) }.readUtf8()).jsonObject
                val (code, text) = replies[minOf(call++, replies.lastIndex)]
                Response
                    .Builder()
                    .request(sent)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("x")
                    .body(text.toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()
    }

    @Test
    fun `api openai com gets max_completion_tokens and low reasoning effort`() {
        val bodies = mutableListOf<JsonObject>()
        updateProfile { it.copy(baseUrl = "https://api.openai.com/v1", maxTokens = 300) }
        val http = fakeHttp(bodies, 200 to completion(threeJson))

        val result = runBlocking { LlmReplyGenerator(profiles, costGuard, http).generate(request) }

        assertThat(result).isInstanceOf(SuggestionResult.Success::class.java)
        val body = bodies.single()
        assertThat(body["max_completion_tokens"]!!.jsonPrimitive.content).isEqualTo("300")
        assertThat(body.containsKey("max_tokens")).isFalse()
        assertThat(body["reasoning_effort"]!!.jsonPrimitive.content).isEqualTo("low")
        assertThat(body.containsKey("reasoning")).isFalse()
    }

    @Test
    fun `other openai-format hosts keep max_tokens and get no reasoning_effort`() {
        server.enqueue(ok(completion(threeJson)))
        generate()
        val body = server.takeRequest().json()
        assertThat(body.containsKey("max_tokens")).isTrue()
        assertThat(body.containsKey("max_completion_tokens")).isFalse()
        assertThat(body.containsKey("reasoning_effort")).isFalse()
    }

    @Test
    fun `a 400 about max_tokens retries once with max_completion_tokens, and remembers per url and model`() {
        val unsupported =
            """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. """ +
                """Use 'max_completion_tokens' instead."}}"""
        server.enqueue(error(400, unsupported))
        server.enqueue(ok(completion(threeJson)))
        server.enqueue(ok(completion(threeJson)))
        val generator = generator()

        assertThat(runBlocking { generator.generate(request) }).isInstanceOf(SuggestionResult.Success::class.java)
        assertThat(runBlocking { generator.generate(request) }).isInstanceOf(SuggestionResult.Success::class.java)

        val first = server.takeRequest().json()
        assertThat(first.containsKey("max_tokens")).isTrue()
        val retry = server.takeRequest().json()
        assertThat(retry.containsKey("max_tokens")).isFalse()
        assertThat(
            retry["max_completion_tokens"]!!.jsonPrimitive.content,
        ).isEqualTo(first["max_tokens"]!!.jsonPrimitive.content)
        assertThat(server.takeRequest().json().containsKey("max_completion_tokens")).isTrue()
        assertThat(costGuard.status.value.requestsToday).isEqualTo(2)

        // Another model on the same URL starts from the default field again.
        updateProfile { it.copy(model = "test/other") }
        server.enqueue(ok(completion(threeJson)))
        runBlocking { generator.generate(request) }
        assertThat(server.takeRequest().json().containsKey("max_tokens")).isTrue()
    }

    @Test
    fun `a 400 about max_completion_tokens on api openai com retries with max_tokens`() {
        val bodies = mutableListOf<JsonObject>()
        updateProfile { it.copy(baseUrl = "https://api.openai.com/v1") }
        val http =
            fakeHttp(
                bodies,
                400 to """{"error":{"message":"Unrecognized request argument supplied: max_completion_tokens"}}""",
                200 to completion(threeJson),
            )

        val result = runBlocking { LlmReplyGenerator(profiles, costGuard, http).generate(request) }

        assertThat(result).isInstanceOf(SuggestionResult.Success::class.java)
        assertThat(bodies.map { it.containsKey("max_completion_tokens") }).containsExactly(true, false).inOrder()
        assertThat(bodies[1].containsKey("max_tokens")).isTrue()
    }

    @Test
    fun `a 400 about reasoning_effort on api openai com retries once without it, and remembers per url and model`() {
        val bodies = mutableListOf<JsonObject>()
        updateProfile { it.copy(baseUrl = "https://api.openai.com/v1", model = "gpt-4.1-mini") }
        val rejected =
            """{"error":{"message":"Unrecognized request argument supplied: reasoning_effort"}}"""
        val http =
            fakeHttp(
                bodies,
                400 to rejected,
                200 to completion(threeJson),
                200 to completion(threeJson),
                200 to completion(threeJson),
            )
        val generator = LlmReplyGenerator(profiles, costGuard, http)

        assertThat(runBlocking { generator.generate(request) }).isInstanceOf(SuggestionResult.Success::class.java)
        assertThat(runBlocking { generator.generate(request) }).isInstanceOf(SuggestionResult.Success::class.java)

        assertThat(bodies.map { it.containsKey("reasoning_effort") }).containsExactly(true, false, false).inOrder()
        // The token field is unaffected by this retry.
        assertThat(bodies.all { it.containsKey("max_completion_tokens") }).isTrue()
        assertThat(costGuard.status.value.requestsToday).isEqualTo(2)

        // Another model on the same URL sends it again.
        updateProfile { it.copy(model = "o9-mini") }
        runBlocking { generator.generate(request) }
        assertThat(bodies.last()["reasoning_effort"]!!.jsonPrimitive.content).isEqualTo("low")
    }

    @Test
    fun `the reasoning_effort retry happens only once`() {
        val bodies = mutableListOf<JsonObject>()
        updateProfile { it.copy(baseUrl = "https://api.openai.com/v1") }
        val rejected = 400 to """{"error":{"message":"Unsupported parameter: 'reasoning_effort'"}}"""
        val http = fakeHttp(bodies, rejected, rejected)

        val result = runBlocking { LlmReplyGenerator(profiles, costGuard, http).generate(request) }

        assertThat((result as SuggestionResult.Failure.Http).code).isEqualTo(400)
        assertThat(bodies).hasSize(2)
        assertThat(bodies[1].containsKey("reasoning_effort")).isFalse()
    }

    @Test
    fun `the token field retry happens only once`() {
        val bodies = mutableListOf<JsonObject>()
        val http = fakeHttp(bodies, 400 to """{"error":{"message":"max_tokens and max_completion_tokens: bad"}}""")
        updateProfile { it.copy(baseUrl = "https://proxy.example.com/v1") }

        val result = runBlocking { LlmReplyGenerator(profiles, costGuard, http).generate(request) }

        assertThat((result as SuggestionResult.Failure.Http).code).isEqualTo(400)
        assertThat(bodies).hasSize(2)
        assertThat(costGuard.status.value.requestsToday).isEqualTo(1)
    }

    @Test
    fun `profile extras are sent - headers, temperature and max tokens`() {
        updateProfile {
            it.copy(
                temperature = 0.3,
                maxTokens = 300,
                extraHeaderNames = listOf("X-Org", "Authorization"),
            )
        }
        runBlocking { secrets.setExtraHeaders(PID, mapOf("X-Org" to "org-secret-1", "Authorization" to "Bearer evil")) }
        server.enqueue(ok(completion(threeJson)))

        assertThat(generate()).isInstanceOf(SuggestionResult.Success::class.java)

        val sent = server.takeRequest()
        assertThat(sent.headers["X-Org"]).isEqualTo("org-secret-1")
        // A reserved header in the extras never replaces the key.
        assertThat(sent.headers["Authorization"]).isEqualTo("Bearer sk-or-test-key")
        val body = sent.json()
        assertThat(body["temperature"]!!.jsonPrimitive.content).isEqualTo("0.3")
        assertThat(body["max_tokens"]!!.jsonPrimitive.content).isEqualTo("300")
    }

    @Test
    fun `no temperature is sent by default`() {
        server.enqueue(ok(completion(threeJson)))
        generate()
        assertThat(server.takeRequest().json().containsKey("temperature")).isFalse()
    }

    @Test
    fun `403, 404 and 529 map to their fixed wording`() {
        assertThat((generateWith(error(403)) as SuggestionResult.Failure.Http).detail).contains("Access denied")
        assertThat((generateWith(error(404)) as SuggestionResult.Failure.Http).detail).contains("not found")
        val overloaded = generateWith(error(529)) as SuggestionResult.Failure.Http
        assertThat(overloaded.code).isEqualTo(529)
        assertThat(overloaded.detail).contains("overloaded")
    }

    @Test
    fun `no active profile means no call`() {
        val empty = InMemoryProfileRepository(emptyList())
        val result =
            runBlocking {
                LlmReplyGenerator(
                    empty,
                    costGuard,
                    LlmReplyGenerator.defaultHttpClient(),
                    requireHttps = false,
                ).generate(request)
            }
        assertThat(result).isEqualTo(SuggestionResult.Failure.NoApiKey)
        assertThat(costGuard.status.value.requestsToday).isEqualTo(0)
    }

    companion object {
        private const val PID = "p1"
    }
}
