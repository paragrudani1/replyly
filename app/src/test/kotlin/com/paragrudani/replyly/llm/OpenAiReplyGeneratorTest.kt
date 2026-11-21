package com.paragrudani.replyly.llm

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.cost.InMemoryCostGuard
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.settings.InMemoryApiKeyStore
import com.paragrudani.replyly.settings.InMemorySettingsRepository
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
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

class OpenAiReplyGeneratorTest {
    private val server = MockWebServer()
    private val keys = InMemoryApiKeyStore()
    private val costGuard = InMemoryCostGuard(dailyRequestLimit = 50)
    private lateinit var settings: InMemorySettingsRepository

    private val request =
        SuggestionRequest(
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            chatTitle = "Ravi",
            isGroup = false,
            history = listOf(ChatMessage("Ravi", "Kal cricket?", "6:40 pm", false)),
            aboutMe = "",
            contactNote = null,
            styleExamples = emptyList(),
            instruction = null,
        )

    @Before
    fun setUp() {
        server.start()
        settings =
            InMemorySettingsRepository(
                ReplylySettings(endpoint = server.url("/api/v1/").toString(), model = "test/model"),
            )
        runBlocking { keys.set("sk-or-test-key") }
    }

    @After
    fun tearDown() = server.close()

    private fun generator(timeouts: LlmTimeouts = LlmTimeouts()) =
        OpenAiReplyGenerator(settings, keys, costGuard, OpenAiReplyGenerator.defaultHttpClient(timeouts))

    private fun generate(timeouts: LlmTimeouts = LlmTimeouts()) = runBlocking { generator(timeouts).generate(request) }

    private fun completion(
        content: String,
        promptTokens: Int = 120,
        completionTokens: Int = 30,
        model: String = "test/model-2026",
    ): String =
        """{"id":"gen-1","model":"$model","choices":[{"index":0,"message":{"role":"assistant",""" +
            """"content":${JsonPrimitive(content)}},"finish_reason":"stop"}],""" +
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
        assertThat(sent.headers["HTTP-Referer"]).isEqualTo(OpenAiReplyGenerator.APP_REFERER)
        assertThat(sent.headers["X-Title"]).isEqualTo("Replyly")
        val body = sent.json()
        assertThat(body["model"]!!.jsonPrimitive.content).isEqualTo("test/model")
        assertThat(body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("json_object")
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertThat(messages.map { it["role"]!!.jsonPrimitive.content }).containsExactly("system", "user").inOrder()
        assertThat(messages[0]["content"]!!.jsonPrimitive.content).isEqualTo(PromptBuilder.SYSTEM_PROMPT)
        assertThat(messages[1]["content"]!!.jsonPrimitive.content).contains("them: Kal cricket?")
        assertThat(
            result.promptSent,
        ).isEqualTo(PromptBuilder.SYSTEM_PROMPT + "\n\n" + messages[1]["content"]!!.jsonPrimitive.content)
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
        runBlocking { keys.set("") }
        assertThat(generate()).isEqualTo(SuggestionResult.Failure.NoApiKey)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(costGuard.status.value.requestsToday).isEqualTo(0)
    }

    @Test
    fun `daily limit reached means no call`() {
        val guard = InMemoryCostGuard(dailyRequestLimit = 1)
        runBlocking { guard.tryAcquire() }
        val result =
            runBlocking {
                OpenAiReplyGenerator(settings, keys, guard, OpenAiReplyGenerator.defaultHttpClient()).generate(request)
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
        runBlocking { settings.update { it.copy(endpoint = "not a url") } }
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
    fun `parallel mode makes 3 single-intent calls and merges them`() {
        runBlocking { settings.update { it.copy(useParallelCalls = true) } }
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val user =
                        request
                            .json()["messages"]!!
                            .jsonArray[1]
                            .jsonObject["content"]!!
                            .jsonPrimitive.content
                    val intent = FixedIntent.entries.first { user.contains("intent \"${it.id}\"") }
                    // The model may echo any intent label; the fixed intent wins.
                    val reply = """{"suggestions":[{"intent":"whatever","text":"reply ${intent.id}"}]}"""
                    return ok(completion(reply, promptTokens = 100, completionTokens = 10))
                }
            }

        val result = generate() as SuggestionResult.Success

        assertThat(server.requestCount).isEqualTo(3)
        assertThat(result.suggestions)
            .containsExactly(
                Suggestion("agree", "reply agree"),
                Suggestion("decline", "reply decline"),
                Suggestion("ask", "reply ask"),
            ).inOrder()
        assertThat(result.usage).isEqualTo(TokenUsage(promptTokens = 300, completionTokens = 30))
        assertThat(result.promptSent).contains("intent \"decline\"")
        assertThat(costGuard.status.value.requestsToday).isEqualTo(1)
        assertThat(costGuard.status.value.totalTokensThisMonth).isEqualTo(330)
    }

    @Test
    fun `parallel mode fails when one call fails, and keeps the others' usage`() {
        runBlocking { settings.update { it.copy(useParallelCalls = true) } }
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val user =
                        request
                            .json()["messages"]!!
                            .jsonArray[1]
                            .jsonObject["content"]!!
                            .jsonPrimitive.content
                    if (user.contains("intent \"ask\"")) return error(503)
                    return ok(completion("""{"suggestions":[{"intent":"x","text":"fine"}]}""", 100, 10))
                }
            }

        val result = generate()

        assertThat((result as SuggestionResult.Failure.Http).code).isEqualTo(503)
        assertThat(costGuard.status.value.requestsToday).isEqualTo(1)
        assertThat(costGuard.status.value.totalTokensThisMonth).isEqualTo(220)
    }
}
