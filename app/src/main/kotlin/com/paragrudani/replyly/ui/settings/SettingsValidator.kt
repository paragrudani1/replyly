package com.paragrudani.replyly.ui.settings

import com.paragrudani.replyly.parser.x.XHandles
import com.paragrudani.replyly.settings.ReplylySettings
import com.paragrudani.replyly.settings.xPostLimit

/** Endpoint and model moved to provider profiles; the form no longer edits or checks them. */
enum class SettingsField {
    HistoryMessageCount,
    AboutMe,
    MyNames,
    MyXHandle,
    RetentionDays,
    DailyRequestLimit,
    MyXVoice,
}

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
    val myNames: String = "",
    /** Shown with a leading `@` when set. */
    val myXHandle: String = "",
    /** "My X voice" (#30 PR 2). */
    val myXVoice: String = "",
    /** "X post length" medium (500, X Premium) instead of standard (280). */
    val xPostMedium: Boolean = false,
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
                myNames = settings.myNames,
                myXHandle =
                    settings.myXHandle
                        .takeIf { it.isNotEmpty() }
                        ?.let { "@$it" }
                        .orEmpty(),
                myXVoice = settings.myXVoice,
                xPostMedium = settings.xPostLimit() == ReplylySettings.X_POST_MEDIUM,
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
    const val MAX_MY_NAMES = 200

    /** "My X voice": the prompt caps it like About me; a few lines is plenty. */
    const val MAX_MY_X_VOICE = 500
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

        val myNames =
            draft.myNames
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")
        if (myNames.length > MAX_MY_NAMES) {
            errors[SettingsField.MyNames] = "At most $MAX_MY_NAMES characters (now ${myNames.length})"
        }

        val myXHandle = XHandles.normalize(draft.myXHandle)
        if (myXHandle == null) errors[SettingsField.MyXHandle] = "Letters, digits and _ only, up to 15 characters"

        val myXVoice = draft.myXVoice.trim()
        if (myXVoice.length > MAX_MY_X_VOICE) {
            errors[SettingsField.MyXVoice] = "At most $MAX_MY_X_VOICE characters (now ${myXVoice.length})"
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
                myNames = myNames,
                myXHandle = myXHandle!!,
                myXVoice = myXVoice,
                xPostLength = if (draft.xPostMedium) ReplylySettings.X_POST_MEDIUM else ReplylySettings.X_POST_STANDARD,
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
