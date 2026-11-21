package com.paragrudani.replyly.ui.settings.profiles

import com.paragrudani.replyly.settings.ApiFormat
import com.paragrudani.replyly.settings.ReplylySettings

/**
 * Starting points for a profile: they prefill the format, the base URL and (where one is known)
 * the model. Model ids checked on 2026-11-21: `anthropic/claude-haiku-5.5` on OpenRouter's
 * `/api/v1/models`, `claude-haiku-5-5` in Anthropic's models overview.
 */
enum class ProfilePreset(
    val label: String,
    /** Null: keep the format already chosen. */
    val format: ApiFormat?,
    val baseUrl: String,
    /** Null: keep the model already typed. */
    val model: String?,
    val keyPlaceholder: String,
) {
    OpenRouter(
        "OpenRouter",
        ApiFormat.OpenAi,
        ReplylySettings.DEFAULT_ENDPOINT,
        ReplylySettings.DEFAULT_MODEL,
        "sk-or-…",
    ),
    OpenAi("OpenAI", ApiFormat.OpenAi, "https://api.openai.com/v1", null, "sk-…"),
    Anthropic("Anthropic", ApiFormat.Anthropic, "https://api.anthropic.com/v1", ANTHROPIC_HAIKU, "sk-ant-…"),
    Custom("Custom", null, "", null, "API key"),
}

/** Anthropic's current Haiku model id (first-party API naming, not OpenRouter's). */
const val ANTHROPIC_HAIKU = "claude-haiku-5-5"

/** The base URL hint shown under the field. */
fun baseUrlHint(format: ApiFormat): String =
    when (format) {
        ApiFormat.OpenAi -> {
            "Include the version path, e.g. https://api.openai.com/v1. /chat/completions is added."
        }

        ApiFormat.Anthropic -> {
            "Include the version path, e.g. https://api.anthropic.com/v1. /messages is added (and /v1 if missing)."
        }
    }

fun ApiFormat.label(): String =
    when (this) {
        ApiFormat.OpenAi -> "OpenAI-compatible"
        ApiFormat.Anthropic -> "Anthropic"
    }

/**
 * Quick picks for the model of an OpenRouter profile (spec 6 shortlist), shown as a radio list in
 * the profile editor. Any other id can still be typed.
 */
val MODEL_SHORTLIST = listOf("anthropic/claude-haiku-5.5", "google/gemini-3.8-flash", "~openai/gpt-mini-latest")

/**
 * The model ids offered as a list in the profile editor for this provider: the shortlist on
 * OpenRouter, Anthropic's Haiku on the Anthropic format, nothing otherwise (OpenAI and custom
 * endpoints name their models too differently to guess). The editor always adds an "Other model
 * id" choice for anything not listed.
 */
fun modelChoices(
    format: ApiFormat,
    baseUrl: String,
): List<String> =
    when {
        format == ApiFormat.OpenAi && "openrouter.ai" in baseUrl -> MODEL_SHORTLIST
        format == ApiFormat.Anthropic -> listOf(ANTHROPIC_HAIKU)
        else -> emptyList()
    }
