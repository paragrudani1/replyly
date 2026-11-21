package com.paragrudani.replyly.ui.settings.profiles

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.settings.ApiFormat
import org.junit.Test

class ProfileValidatorTest {
    private val valid =
        ProfileDraft(
            id = null,
            name = " Anthropic ",
            format = ApiFormat.Anthropic,
            baseUrl = "https://api.anthropic.com/v1/",
            model = "claude-haiku-5-5",
        )

    private fun validate(draft: ProfileDraft) = ProfileValidator.validate(draft) { "new-id" }

    private fun errors(draft: ProfileDraft) = (validate(draft) as ProfileValidation.Invalid).errors

    @Test
    fun `a valid draft is cleaned`() {
        val result = validate(valid) as ProfileValidation.Valid
        assertThat(result.profile.id).isEqualTo("new-id")
        assertThat(result.profile.name).isEqualTo("Anthropic")
        assertThat(result.profile.baseUrl).isEqualTo("https://api.anthropic.com/v1")
        assertThat(result.profile.maxTokens).isNull()
        assertThat(result.profile.temperature).isNull()
        assertThat(result.apiKey).isNull()
    }

    @Test
    fun `https only, with a name and a model`() {
        assertThat(errors(valid.copy(baseUrl = "http://api.anthropic.com/v1"))).containsKey(ProfileField.BaseUrl)
        assertThat(errors(valid.copy(name = "  "))).containsKey(ProfileField.Name)
        assertThat(errors(valid.copy(model = "has space"))).containsKey(ProfileField.Model)
    }

    @Test
    fun `optional numbers are bounded`() {
        assertThat(
            (validate(valid.copy(maxTokens = "2048")) as ProfileValidation.Valid).profile.maxTokens,
        ).isEqualTo(2048)
        assertThat(errors(valid.copy(maxTokens = "0"))).containsKey(ProfileField.MaxTokens)
        val openAi = valid.copy(format = ApiFormat.OpenAi, baseUrl = "https://api.openai.com/v1", model = "m")
        assertThat(
            (validate(openAi.copy(temperature = "0.7")) as ProfileValidation.Valid).profile.temperature,
        ).isEqualTo(0.7)
        assertThat(errors(openAi.copy(temperature = "3"))).containsKey(ProfileField.Temperature)
    }

    @Test
    fun `temperature is dropped for the Anthropic format`() {
        val result = validate(valid.copy(temperature = "0.5")) as ProfileValidation.Valid
        assertThat(result.profile.temperature).isNull()
    }

    @Test
    fun `api key rules match the settings screen`() {
        assertThat(errors(valid.copy(apiKeyInput = "short"))).containsKey(ProfileField.ApiKey)
        assertThat((validate(valid.copy(apiKeyInput = " sk-ant-1234567890 ")) as ProfileValidation.Valid).apiKey)
            .isEqualTo("sk-ant-1234567890")
    }

    @Test
    fun `header rules`() {
        fun headers(vararg h: HeaderInput) = valid.copy(newHeaders = h.toList())
        assertThat(errors(headers(HeaderInput("Bad Name", "v")))).containsKey(ProfileField.Headers)
        assertThat(errors(headers(HeaderInput("X-Api-Key", "v")))).containsKey(ProfileField.Headers)
        assertThat(errors(headers(HeaderInput("authorization", "v")))).containsKey(ProfileField.Headers)
        assertThat(errors(headers(HeaderInput("X-A", "v"), HeaderInput("x-a", "w")))).containsKey(ProfileField.Headers)
        assertThat(errors(headers(HeaderInput("X-A", "line\nbreak")))).containsKey(ProfileField.Headers)
        assertThat(errors(headers(HeaderInput("X-A", "")))).containsKey(ProfileField.Headers)
        val tooMany = valid.copy(newHeaders = List(11) { HeaderInput("X-$it", "v") })
        assertThat(errors(tooMany)).containsKey(ProfileField.Headers)
    }

    @Test
    fun `new headers replace saved ones with the same name, blank rows are ignored`() {
        val draft =
            valid.copy(
                id = "p",
                savedHeaderNames = listOf("X-Org", "X-Keep"),
                newHeaders = listOf(HeaderInput("x-org", "new"), HeaderInput()),
            )
        val result = validate(draft) as ProfileValidation.Valid
        assertThat(result.keptHeaderNames).containsExactly("X-Keep")
        assertThat(result.newHeaders).containsExactly("x-org", "new")
        assertThat(result.profile.extraHeaderNames).containsExactly("X-Keep", "x-org").inOrder()
    }

    @Test
    fun `toString never shows secrets`() {
        val draft = valid.copy(apiKeyInput = "sk-ant-secret-1", newHeaders = listOf(HeaderInput("X-A", "hdr-secret")))
        assertThat(draft.toString()).doesNotContain("sk-ant-secret")
        assertThat(draft.toString()).doesNotContain("hdr-secret")
        val result = validate(draft.copy(apiKeyInput = "sk-ant-secret-12")) as ProfileValidation.Valid
        assertThat(result.toString()).doesNotContain("sk-ant-secret")
        assertThat(result.toString()).doesNotContain("hdr-secret")
    }

    @Test
    fun `presets prefill format, base URL and model`() {
        val anthropic = ProfileDraft(id = null).withPreset(ProfilePreset.Anthropic)
        assertThat(anthropic.format).isEqualTo(ApiFormat.Anthropic)
        assertThat(anthropic.baseUrl).isEqualTo("https://api.anthropic.com/v1")
        assertThat(anthropic.model).isEqualTo("claude-haiku-5-5")
        assertThat(anthropic.name).isEqualTo("Anthropic")

        val openRouter = anthropic.withPreset(ProfilePreset.OpenRouter)
        assertThat(openRouter.format).isEqualTo(ApiFormat.OpenAi)
        assertThat(openRouter.model).isEqualTo("anthropic/claude-haiku-5.5")
        assertThat(openRouter.name).isEqualTo("OpenRouter")

        val custom = openRouter.copy(name = "My proxy").withPreset(ProfilePreset.Custom)
        assertThat(custom.name).isEqualTo("My proxy")
        assertThat(custom.format).isEqualTo(ApiFormat.OpenAi)
        assertThat(custom.baseUrl).isEmpty()
        assertThat(custom.model).isEqualTo("anthropic/claude-haiku-5.5")
    }
}
