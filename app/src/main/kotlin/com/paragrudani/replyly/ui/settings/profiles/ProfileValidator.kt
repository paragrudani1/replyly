package com.paragrudani.replyly.ui.settings.profiles

import com.paragrudani.replyly.llm.LlmHttp
import com.paragrudani.replyly.llm.ProviderUrls
import com.paragrudani.replyly.settings.ApiFormat
import com.paragrudani.replyly.settings.ProviderProfile
import com.paragrudani.replyly.ui.settings.SettingsValidator

enum class ProfileField { Name, BaseUrl, Model, MaxTokens, Temperature, ApiKey, Headers }

/** A header typed in the editor. The value is a secret: never log it. */
data class HeaderInput(
    val name: String = "",
    val value: String = "",
) {
    override fun toString(): String = "HeaderInput(name=$name, value=<${value.length} chars>)"
}

/** What the profile editor edits. Numbers stay text until saved. [toString] hides secrets. */
data class ProfileDraft(
    /** Null for a profile that is not saved yet. */
    val id: String?,
    val name: String = "",
    val format: ApiFormat = ApiFormat.OpenAi,
    val baseUrl: String = "",
    val model: String = "",
    val maxTokens: String = "",
    val temperature: String = "",
    /** Saved headers the user keeps (values stay encrypted; never loaded into the UI). */
    val savedHeaderNames: List<String> = emptyList(),
    val newHeaders: List<HeaderInput> = emptyList(),
    /** Typed key; blank keeps the saved one. */
    val apiKeyInput: String = "",
    val keySaved: Boolean = false,
    val replacingKey: Boolean = false,
    /** "Remove" was tapped: the saved key is deleted on Save; Cancel keeps it. */
    val removeKey: Boolean = false,
    val keyPlaceholder: String = "API key",
) {
    val showKeyField: Boolean get() = !keySaved || replacingKey || removeKey

    override fun toString(): String =
        "ProfileDraft(id=$id, name=$name, format=$format, baseUrl=$baseUrl, model=$model, maxTokens=$maxTokens, " +
            "temperature=$temperature, savedHeaderNames=$savedHeaderNames, newHeaders=$newHeaders, " +
            "apiKeyInput=<${apiKeyInput.length} chars>, keySaved=$keySaved, removeKey=$removeKey)"

    fun withPreset(preset: ProfilePreset): ProfileDraft {
        val presetNames = ProfilePreset.entries.map { it.label }
        return copy(
            name = if (name.isBlank() || name.trim() in presetNames) preset.label else name,
            format = preset.format ?: format,
            baseUrl = preset.baseUrl,
            model = preset.model ?: if (preset == ProfilePreset.Custom) model else "",
            keyPlaceholder = preset.keyPlaceholder,
        )
    }

    companion object {
        fun from(
            profile: ProviderProfile,
            keySaved: Boolean,
        ) = ProfileDraft(
            id = profile.id,
            name = profile.name,
            format = profile.format,
            baseUrl = profile.baseUrl,
            model = profile.model,
            maxTokens = profile.maxTokens?.toString().orEmpty(),
            temperature = profile.temperature?.toString().orEmpty(),
            savedHeaderNames = profile.extraHeaderNames,
            keySaved = keySaved,
        )
    }
}

sealed interface ProfileValidation {
    /**
     * [profile] has the cleaned fields; its [ProviderProfile.extraHeaderNames] are the kept saved
     * names plus the new ones. [newHeaders] replace saved values of the same name.
     */
    data class Valid(
        val profile: ProviderProfile,
        val keptHeaderNames: List<String>,
        val newHeaders: Map<String, String>,
        /** Null: leave the saved key as it is (or remove it, see [removeKey]). */
        val apiKey: String?,
        /** Delete the saved key (only when no new [apiKey] was typed). */
        val removeKey: Boolean = false,
    ) : ProfileValidation {
        override fun toString(): String =
            "Valid(profile=$profile, newHeaders=${newHeaders.keys}, hasKey=${apiKey != null}, removeKey=$removeKey)"
    }

    data class Invalid(
        val errors: Map<ProfileField, String>,
    ) : ProfileValidation
}

/** Profile input rules. Messages never echo the key or a header value. */
object ProfileValidator {
    const val MAX_NAME = 40
    const val MAX_TOKENS = 64_000
    const val MAX_TEMPERATURE = 2.0
    const val MAX_HEADERS = 10
    private const val MAX_MODEL = 120
    private const val MAX_HEADER_VALUE = 2_000

    private val baseUrlPattern = Regex("""^https://[^\s/?#]+(/\S*)?$""")
    private val modelPattern = Regex("""^[A-Za-z0-9._:/~@+\-]+$""")

    /** RFC 9110 token characters. */
    private val headerNamePattern = Regex("""^[A-Za-z0-9!#$%&'*+.^_`|~-]+$""")

    /** Headers that carry the key or frame the request; the LLM client sets them itself. */
    val RESERVED_HEADERS: Set<String> get() = LlmHttp.RESERVED_HEADERS

    const val KEY_FOR_NEW_HOST = "Enter the key for this provider"

    /**
     * [stored] is the saved version of the profile being edited. If the host or format changes,
     * the saved key must not follow it there: a new key must be typed, or the key removed.
     */
    fun validate(
        draft: ProfileDraft,
        stored: ProviderProfile? = null,
        newId: () -> String,
    ): ProfileValidation {
        val errors = mutableMapOf<ProfileField, String>()

        val name = draft.name.trim()
        if (name.isEmpty() ||
            name.length > MAX_NAME
        ) {
            errors[ProfileField.Name] = "Enter a name of at most $MAX_NAME characters"
        }

        val baseUrl = draft.baseUrl.trim().trimEnd('/')
        if (!baseUrlPattern.matches(baseUrl) || ProviderUrls.endpoint(draft.format, baseUrl) == null) {
            errors[ProfileField.BaseUrl] = "Enter an https:// URL with the version path, e.g. https://api.openai.com/v1"
        } else if (hasUserInfo(baseUrl)) {
            errors[ProfileField.BaseUrl] = "Leave out user:password@; put credentials in the key or a header"
        }

        val model = draft.model.trim()
        if (model.isEmpty() || model.length > MAX_MODEL || !modelPattern.matches(model)) {
            errors[ProfileField.Model] = "Enter a model id without spaces"
        }

        val maxTokens =
            draft.maxTokens.trim().takeIf { it.isNotEmpty() }?.let { text ->
                text.toIntOrNull()?.takeIf { it in 1..MAX_TOKENS }.also {
                    if (it ==
                        null
                    ) {
                        errors[ProfileField.MaxTokens] = "Whole number from 1 to $MAX_TOKENS, or leave empty"
                    }
                }
            }

        // Only the OpenAI format sends a temperature; it is dropped for Anthropic.
        val temperature =
            draft.temperature
                .trim()
                .takeIf { it.isNotEmpty() && draft.format == ApiFormat.OpenAi }
                ?.let { text ->
                    text.toDoubleOrNull()?.takeIf { it in 0.0..MAX_TEMPERATURE }.also {
                        if (it == null) errors[ProfileField.Temperature] = "Number from 0 to 2, or leave empty"
                    }
                }

        val apiKey = draft.apiKeyInput.trim().takeIf { it.isNotEmpty() }
        apiKey?.let { SettingsValidator.apiKeyError(it) }?.let { errors[ProfileField.ApiKey] = it }
        val removeKey = draft.removeKey && apiKey == null
        if (stored != null && draft.keySaved && apiKey == null && !removeKey &&
            movesKey(stored, draft.format, baseUrl)
        ) {
            errors[ProfileField.ApiKey] = KEY_FOR_NEW_HOST
        }

        val newHeaders = draft.newHeaders.filterNot { it.name.isBlank() && it.value.isBlank() }
        headerError(draft.savedHeaderNames, newHeaders)?.let { errors[ProfileField.Headers] = it }

        if (errors.isNotEmpty()) return ProfileValidation.Invalid(errors)
        val added = newHeaders.associate { it.name.trim() to it.value.trim() }
        val kept = draft.savedHeaderNames.filterNot { saved -> added.keys.any { it.equals(saved, ignoreCase = true) } }
        return ProfileValidation.Valid(
            profile =
                ProviderProfile(
                    id = draft.id ?: newId(),
                    name = name,
                    format = draft.format,
                    baseUrl = baseUrl,
                    model = model,
                    extraHeaderNames = kept + added.keys,
                    maxTokens = maxTokens,
                    temperature = temperature,
                ),
            keptHeaderNames = kept,
            newHeaders = added,
            apiKey = apiKey,
            removeKey = removeKey,
        )
    }

    /** True when the key would go to a different host or speak a different format than before. */
    private fun movesKey(
        stored: ProviderProfile,
        format: ApiFormat,
        baseUrl: String,
    ): Boolean {
        if (format != stored.format) return true
        val before = ProviderUrls.endpoint(stored.format, stored.baseUrl)?.host ?: return true
        val after = ProviderUrls.endpoint(format, baseUrl)?.host ?: return true
        return !before.equals(after, ignoreCase = true)
    }

    /** `https://user:pw@host/...`: credentials would be stored in plain text and shown in the preview. */
    private fun hasUserInfo(baseUrl: String): Boolean = baseUrl.substringAfter("://").substringBefore('/').contains('@')

    private fun headerError(
        saved: List<String>,
        added: List<HeaderInput>,
    ): String? {
        val names = added.map { it.name.trim() }
        return when {
            saved.size + added.size > MAX_HEADERS -> {
                "At most $MAX_HEADERS extra headers"
            }

            names.any { !headerNamePattern.matches(it) } -> {
                "Header names use letters, digits and - only, e.g. X-Org-Id"
            }

            names.any { it.lowercase() in RESERVED_HEADERS } -> {
                "Authorization, x-api-key, Content-Type, Content-Length, Host and Accept-Encoding are set by Replyly"
            }

            names.map { it.lowercase() }.toSet().size != names.size -> {
                "Each header name only once"
            }

            added.any { h ->
                h.value.isBlank() || h.value.length > MAX_HEADER_VALUE || h.value.any { it !in ' '..'~' }
            } -> {
                "Header values must be plain text (no line breaks or special characters)"
            }

            else -> {
                null
            }
        }
    }
}
