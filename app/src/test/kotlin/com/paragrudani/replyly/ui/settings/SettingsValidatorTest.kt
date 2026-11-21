package com.paragrudani.replyly.ui.settings

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.settings.ReplylySettings
import org.junit.Test

class SettingsValidatorTest {
    private val valid = SettingsDraft.from(ReplylySettings())

    private fun errorsOf(draft: SettingsDraft) = (SettingsValidator.validate(draft) as DraftValidation.Invalid).errors

    @Test
    fun `the defaults are valid and map back to the same settings`() {
        val result = SettingsValidator.validate(valid)
        assertThat(result).isInstanceOf(DraftValidation.Valid::class.java)
        assertThat((result as DraftValidation.Valid).apply(ReplylySettings())).isEqualTo(ReplylySettings())
    }

    @Test
    fun `values are trimmed and the endpoint loses its trailing slash`() {
        val draft =
            valid.copy(
                endpoint = "  https://openrouter.ai/api/v1/ ",
                model = " google/gemini-3.8-flash ",
                historyMessageCount = " 25 ",
                aboutMe = "  Short replies.  ",
            )
        val settings = (SettingsValidator.validate(draft) as DraftValidation.Valid).apply(ReplylySettings())
        assertThat(settings.endpoint).isEqualTo("https://openrouter.ai/api/v1")
        assertThat(settings.model).isEqualTo("google/gemini-3.8-flash")
        assertThat(settings.historyMessageCount).isEqualTo(25)
        assertThat(settings.aboutMe).isEqualTo("Short replies.")
    }

    @Test
    fun `the endpoint must be an https url`() {
        assertThat(errorsOf(valid.copy(endpoint = "http://openrouter.ai/api/v1"))).containsKey(SettingsField.Endpoint)
        assertThat(errorsOf(valid.copy(endpoint = "openrouter.ai"))).containsKey(SettingsField.Endpoint)
        assertThat(errorsOf(valid.copy(endpoint = "https://"))).containsKey(SettingsField.Endpoint)
        assertThat(errorsOf(valid.copy(endpoint = "https://open router.ai"))).containsKey(SettingsField.Endpoint)
        assertThat(errorsOf(valid.copy(endpoint = ""))).containsKey(SettingsField.Endpoint)
    }

    @Test
    fun `the shortlist models and free-text ids are accepted, blanks and spaces are not`() {
        listOf(
            "anthropic/claude-haiku-5.5",
            "google/gemini-3.8-flash",
            "~openai/gpt-mini-latest",
            "meta-llama/llama-5:free",
        ).forEach {
            assertThat(
                SettingsValidator.validate(valid.copy(model = it)),
            ).isInstanceOf(DraftValidation.Valid::class.java)
        }
        assertThat(errorsOf(valid.copy(model = " "))).containsKey(SettingsField.Model)
        assertThat(errorsOf(valid.copy(model = "claude haiku"))).containsKey(SettingsField.Model)
    }

    @Test
    fun `numbers must be whole and in range`() {
        assertThat(errorsOf(valid.copy(historyMessageCount = "0"))).containsKey(SettingsField.HistoryMessageCount)
        assertThat(errorsOf(valid.copy(historyMessageCount = "201"))).containsKey(SettingsField.HistoryMessageCount)
        assertThat(errorsOf(valid.copy(historyMessageCount = "4.5"))).containsKey(SettingsField.HistoryMessageCount)
        assertThat(errorsOf(valid.copy(retentionDays = "0"))).containsKey(SettingsField.RetentionDays)
        assertThat(errorsOf(valid.copy(retentionDays = "366"))).containsKey(SettingsField.RetentionDays)
        assertThat(errorsOf(valid.copy(dailyRequestLimit = ""))).containsKey(SettingsField.DailyRequestLimit)
        assertThat(errorsOf(valid.copy(dailyRequestLimit = "5001"))).containsKey(SettingsField.DailyRequestLimit)
        assertThat(
            SettingsValidator.validate(
                valid.copy(historyMessageCount = "200", retentionDays = "365", dailyRequestLimit = "1"),
            ),
        ).isInstanceOf(DraftValidation.Valid::class.java)
    }

    @Test
    fun `about me is capped`() {
        assertThat(errorsOf(valid.copy(aboutMe = "x".repeat(1001)))).containsKey(SettingsField.AboutMe)
    }

    @Test
    fun `all errors are reported at once`() {
        assertThat(errorsOf(valid.copy(endpoint = "x", model = "", retentionDays = "-1")).keys)
            .containsExactly(SettingsField.Endpoint, SettingsField.Model, SettingsField.RetentionDays)
    }

    @Test
    fun `Save never touches blocklists`() {
        // A stale draft must not undo a blocklist change stored since the form loaded.
        val draft = valid.copy(blockedPackages = setOf("com.stale.app"), blockedChatKeys = emptySet())
        val stored =
            ReplylySettings(blockedPackages = setOf("com.bank.app"), blockedChatKeys = setOf("com.whatsapp:Mom"))
        val settings = (SettingsValidator.validate(draft) as DraftValidation.Valid).apply(stored)
        assertThat(settings.blockedPackages).containsExactly("com.bank.app")
        assertThat(settings.blockedChatKeys).containsExactly("com.whatsapp:Mom")
    }

    @Test
    fun `package names`() {
        assertThat(SettingsValidator.normalizePackage(" com.whatsapp.w4b ")).isEqualTo("com.whatsapp.w4b")
        assertThat(SettingsValidator.normalizePackage("org.telegram.messenger")).isEqualTo("org.telegram.messenger")
        assertThat(SettingsValidator.normalizePackage("whatsapp")).isNull()
        assertThat(SettingsValidator.normalizePackage("com..whatsapp")).isNull()
        assertThat(SettingsValidator.normalizePackage("com.what sapp")).isNull()
        assertThat(SettingsValidator.normalizePackage("1com.app")).isNull()
    }

    @Test
    fun `chat keys are package colon title`() {
        assertThat(SettingsValidator.normalizeChatKey("com.whatsapp: Bank OTP ")).isEqualTo("com.whatsapp:Bank OTP")
        assertThat(
            SettingsValidator.normalizeChatKey("org.telegram.messenger:Work: HR"),
        ).isEqualTo("org.telegram.messenger:Work: HR")
        assertThat(SettingsValidator.normalizeChatKey("com.whatsapp:")).isNull()
        assertThat(SettingsValidator.normalizeChatKey("Mom")).isNull()
        assertThat(SettingsValidator.normalizeChatKey("whatsapp:Mom")).isNull()
    }

    @Test
    fun `api keys`() {
        assertThat(SettingsValidator.apiKeyError("sk-or-v1-0123456789abcdef")).isNull()
        assertThat(SettingsValidator.apiKeyError(" sk-or-v1-0123456789abcdef\n")).isNull()
        assertThat(SettingsValidator.apiKeyError("")).isNotNull()
        assertThat(SettingsValidator.apiKeyError("short")).isNotNull()
        assertThat(SettingsValidator.apiKeyError("sk-or v1-0123456789")).isNotNull()
    }
}
