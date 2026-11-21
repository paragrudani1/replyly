package com.paragrudani.replyly.llm

import com.paragrudani.replyly.settings.ApiFormat
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Turns a profile's base URL into the request URL. The user enters the base URL including its
 * version path (`https://openrouter.ai/api/v1`, `https://api.openai.com/v1`,
 * `https://api.anthropic.com/v1`):
 *
 * - OpenAI format appends `/chat/completions`.
 * - Anthropic format appends `/messages`. If the URL has no version segment (`v1`, `v2`...), `v1`
 *   is added first, so `https://api.anthropic.com` works too.
 * - A pasted full endpoint (ending in `/chat/completions` or `/messages`) is not doubled.
 * - Trailing slashes are ignored; a query string is kept.
 *
 * Invariants (seeded property test in `ProviderUrlsTest`): the result keeps the scheme, host, port and
 * query of the input, its path ends with the format's suffix exactly once, and it has no empty
 * path segments.
 */
object ProviderUrls {
    private val VERSION_SEGMENT = Regex("""v\d+""")

    internal fun endpoint(
        format: ApiFormat,
        baseUrl: String,
    ): HttpUrl? {
        val base = baseUrl.trim().toHttpUrlOrNull() ?: return null
        val segments = base.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        val suffix =
            when (format) {
                ApiFormat.OpenAi -> listOf("chat", "completions")
                ApiFormat.Anthropic -> listOf("messages")
            }
        while (segments.size >= suffix.size && segments.takeLast(suffix.size) == suffix) {
            repeat(suffix.size) { segments.removeAt(segments.lastIndex) }
        }
        if (format == ApiFormat.Anthropic && segments.none { VERSION_SEGMENT.matches(it) }) segments += "v1"
        val builder = base.newBuilder().encodedPath("/")
        (segments + suffix).forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    /** The URL requests will go to, for the editor's hint; null if [baseUrl] is not a URL. */
    fun preview(
        format: ApiFormat,
        baseUrl: String,
    ): String? = endpoint(format, baseUrl)?.toString()
}
