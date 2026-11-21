package com.paragrudani.replyly.llm

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.settings.ApiFormat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import kotlin.random.Random

class ProviderUrlsTest {
    private fun openAi(base: String) = ProviderUrls.endpoint(ApiFormat.OpenAi, base)?.toString()

    private fun anthropic(base: String) = ProviderUrls.endpoint(ApiFormat.Anthropic, base)?.toString()

    @Test
    fun `openai format appends chat completions`() {
        assertThat(openAi("https://openrouter.ai/api/v1")).isEqualTo("https://openrouter.ai/api/v1/chat/completions")
        assertThat(openAi("https://api.openai.com/v1/")).isEqualTo("https://api.openai.com/v1/chat/completions")
        assertThat(openAi(" https://proxy.example.com ")).isEqualTo("https://proxy.example.com/chat/completions")
    }

    @Test
    fun `anthropic format appends messages and adds v1 only when no version is present`() {
        assertThat(anthropic("https://api.anthropic.com/v1")).isEqualTo("https://api.anthropic.com/v1/messages")
        assertThat(anthropic("https://api.anthropic.com")).isEqualTo("https://api.anthropic.com/v1/messages")
        assertThat(anthropic("https://api.anthropic.com/")).isEqualTo("https://api.anthropic.com/v1/messages")
        assertThat(
            anthropic("https://proxy.example.com/anthropic"),
        ).isEqualTo("https://proxy.example.com/anthropic/v1/messages")
        assertThat(anthropic("https://proxy.example.com/v2")).isEqualTo("https://proxy.example.com/v2/messages")
    }

    @Test
    fun `a pasted full endpoint is not doubled`() {
        assertThat(
            openAi("https://api.openai.com/v1/chat/completions"),
        ).isEqualTo("https://api.openai.com/v1/chat/completions")
        assertThat(
            anthropic("https://api.anthropic.com/v1/messages"),
        ).isEqualTo("https://api.anthropic.com/v1/messages")
    }

    @Test
    fun `query and port are kept`() {
        assertThat(openAi("https://proxy.example.com:8443/openai/v1?api-version=2026-01-01"))
            .isEqualTo("https://proxy.example.com:8443/openai/v1/chat/completions?api-version=2026-01-01")
    }

    @Test
    fun `not a URL gives null`() {
        assertThat(openAi("not a url")).isNull()
        assertThat(anthropic("")).isNull()
    }

    /**
     * Seeded property test over random base URLs. Invariants: scheme, host, port and query are
     * kept; the path ends with the format's suffix exactly once; no empty path segments; for
     * Anthropic a version segment is present.
     */
    @Test
    fun `property - joining keeps the origin and ends with the suffix once`() {
        val random = Random(18)
        val pieces = listOf("api", "v1", "v2", "openai", "anthropic", "chat", "completions", "messages", "proxy", "")
        repeat(2_000) {
            val host =
                listOf(
                    "openrouter.ai",
                    "api.openai.com",
                    "api.anthropic.com",
                    "proxy.example.com",
                ).random(random)
            val port = if (random.nextBoolean()) "" else ":${random.nextInt(1_000, 9_999)}"
            val path = List(random.nextInt(0, 5)) { pieces.random(random) }.joinToString("/")
            val slash = if (random.nextBoolean()) "/" else ""
            val query = if (random.nextInt(4) == 0) "?k=${random.nextInt(100)}" else ""
            val base = "https://$host$port/$path$slash$query"
            for (format in ApiFormat.entries) {
                val url = ProviderUrls.endpoint(format, base) ?: error("null for $base")
                val input = base.toHttpUrl()
                assertThat(url.scheme).isEqualTo(input.scheme)
                assertThat(url.host).isEqualTo(input.host)
                assertThat(url.port).isEqualTo(input.port)
                assertThat(url.query).isEqualTo(input.query)
                assertThat(url.pathSegments).doesNotContain("")
                val suffix = if (format == ApiFormat.OpenAi) listOf("chat", "completions") else listOf("messages")
                assertThat(url.pathSegments.takeLast(suffix.size)).isEqualTo(suffix)
                val withoutSuffix = url.pathSegments.dropLast(suffix.size)
                assertThat(withoutSuffix.takeLast(suffix.size)).isNotEqualTo(suffix)
                if (format == ApiFormat.Anthropic) {
                    assertThat(withoutSuffix.any { Regex("v\\d+").matches(it) }).isTrue()
                }
            }
        }
    }
}
