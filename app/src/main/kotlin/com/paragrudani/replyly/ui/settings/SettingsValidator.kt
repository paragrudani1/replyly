package com.paragrudani.replyly.ui.settings

import com.paragrudani.replyly.settings.ReplylySettings

/** Endpoint and model moved to provider profiles; the form no longer edits or checks them. */
enum class SettingsField { HistoryMessageCount, AboutMe, RetentionDays, DailyRequestLimit }

/** What the settings form edits: numbers stay text until saved, so half-typed input is fine. */
data class SettingsDraft(
    val endpoint: String,
    val model: String,
    val historyMessageCount: String,
    val aboutMe: String,
    val retentionDays: String,
    val dailyRequestLimit: String,
    val blockedPackages: Set<String>,
    val blockedChatKeys: Set<String>,
    val useParallelCalls: Boolean = false,
) {
    companion object {
        fun from(settings: ReplylySettings) =
            SettingsDraft(
                endpoint = settings.endpoint,
                model = settings.model,
                historyMessageCount = settings.historyMessageCount.toString(),
                aboutMe = settings.aboutMe,
                retentionDays = settings.retentionDays.toString(),
                dailyRequestLimit = settings.dailyRequestLimit.toString(),
                blockedPackages = settings.blockedPackages,
                blockedChatKeys = settings.blockedChatKeys,
                useParallelCalls = settings.useParallelCalls,
            )
    }
}

sealed interface DraftValidation {
    /** [apply] writes the cleaned values onto the stored settings, leaving the stored blocklists as they are. */
    data class Valid(
        val apply: (ReplylySettings) -> ReplylySettings,
    ) : DraftValidation

    data class Invalid(
        val errors: Map<SettingsField, String>,
    ) : DraftValidation
}

/** Settings input rules. Messages are for the screen; none of them echo the API key. */
object SettingsValidator {
    const val MAX_HISTORY = 200
    const val MAX_RETENTION_DAYS = 365
    const val MAX_DAILY_LIMIT = 5000
    const val MAX_ABOUT_ME = 1000
    private const val MIN_API_KEY = 8

    private val packagePattern = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$""")

    fun validate(draft: SettingsDraft): DraftValidation {
        val errors = mutableMapOf<SettingsField, String>()

        val history = draft.historyMessageCount.wholeIn(1..MAX_HISTORY)
        if (history == null) errors[SettingsField.HistoryMessageCount] = "Whole number from 1 to $MAX_HISTORY"

        val aboutMe = draft.aboutMe.trim()
        if (aboutMe.length >
            MAX_ABOUT_ME
        ) {
            errors[SettingsField.AboutMe] = "At most $MAX_ABOUT_ME characters (now ${aboutMe.length})"
        }

        val retention = draft.retentionDays.wholeIn(1..MAX_RETENTION_DAYS)
        if (retention ==
            null
        ) {
            errors[SettingsField.RetentionDays] = "Whole number of days from 1 to $MAX_RETENTION_DAYS"
        }

        val limit = draft.dailyRequestLimit.wholeIn(1..MAX_DAILY_LIMIT)
        if (limit == null) errors[SettingsField.DailyRequestLimit] = "Whole number from 1 to $MAX_DAILY_LIMIT"

        if (errors.isNotEmpty()) return DraftValidation.Invalid(errors)
        return DraftValidation.Valid { current ->
            current.copy(
                historyMessageCount = history!!,
                aboutMe = aboutMe,
                retentionDays = retention!!,
                dailyRequestLimit = limit!!,
                useParallelCalls = draft.useParallelCalls,
                // Blocklists are saved on their own the moment they change; Save keeps the stored ones.
            )
        }
    }

    /** @return the cleaned package name, or null if it is not one. */
    fun normalizePackage(input: String): String? = input.trim().takeIf { packagePattern.matches(it) }

    /** `"<package>:<chat title>"`, as in [com.paragrudani.replyly.parser.ChatKey.encoded]. */
    fun normalizeChatKey(input: String): String? {
        val trimmed = input.trim()
        val split = trimmed.indexOf(':')
        if (split <= 0) return null
        val pkg = normalizePackage(trimmed.substring(0, split)) ?: return null
        val title = trimmed.substring(split + 1).trim()
        return if (title.isEmpty()) null else "$pkg:$title"
    }

    /** @return why [input] is not a usable API key, or null if it is. */
    fun apiKeyError(input: String): String? {
        val key = input.trim()
        return when {
            key.isEmpty() -> "Paste your API key"
            key.any { it.isWhitespace() } -> "The key cannot contain spaces"
            key.length < MIN_API_KEY -> "That looks too short for an API key"
            else -> null
        }
    }

    private fun String.wholeIn(range: IntRange): Int? = trim().toIntOrNull()?.takeIf { it in range }
}
