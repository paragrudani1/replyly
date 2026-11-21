package com.paragrudani.replyly.ui.settings.profiles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.settings.ApiFormat
import com.paragrudani.replyly.settings.ProviderProfile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfilesUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val openRouter =
        ProviderProfile(
            "or",
            "OpenRouter",
            ApiFormat.OpenAi,
            "https://openrouter.ai/api/v1",
            "anthropic/claude-haiku-5.5",
        )
    private val anthropic =
        ProviderProfile("an", "Anthropic", ApiFormat.Anthropic, "https://api.anthropic.com/v1", "claude-haiku-5-5")

    @Test
    fun `list shows profiles, the missing key, test results, and switches on tap`() {
        val selected = mutableListOf<String>()
        val state =
            ProfilesUiState(
                loaded = true,
                rows =
                    listOf(
                        ProfileRow(openRouter, active = true, hasKey = true),
                        ProfileRow(anthropic, active = false, hasKey = false),
                    ),
                tests =
                    mapOf(
                        "or" to TestState.Ok(840),
                        "an" to TestState.Failed("API key rejected (401). Check the key in Settings."),
                    ),
            )
        compose.setContent {
            ProviderProfilesContent(state, onSelect = { selected += it }, onEdit = {}, onTest = {}, onAdd = {})
        }

        compose.onNodeWithText("OK · 840 ms").assertExists()
        compose.onNodeWithText("API key rejected (401). Check the key in Settings.").assertExists()
        compose.onNodeWithText("No API key").assertExists()
        compose.onNodeWithText("Anthropic · claude-haiku-5-5").assertExists()
        compose.onNodeWithText("Anthropic · claude-haiku-5-5").performClick()
        assertThat(selected).containsExactly("an")
    }

    @Test
    fun `editor shows the final URL, hides saved secrets and has no temperature for Anthropic`() {
        val draft =
            ProfileDraft.from(
                anthropic.copy(baseUrl = "https://api.anthropic.com", extraHeaderNames = listOf("X-Org")),
                keySaved = true,
            )
        compose.setContent {
            ProfileEditor(
                draft = draft,
                errors = emptyMap(),
                canDelete = false,
                message = null,
                actions = ProfileEditorActions({}, {}, {}, {}, {}, {}, {}),
            )
        }

        compose.onNodeWithText("Requests go to https://api.anthropic.com/v1/messages", substring = true).assertExists()
        compose.onNodeWithText("API key: saved ••••••••").assertExists()
        compose.onNodeWithText("X-Org: value saved").performScrollTo().assertExists()
        compose.onNodeWithText("Temperature (optional)").assertDoesNotExist()
        compose.onNodeWithText("Delete profile").performScrollTo().assertIsNotEnabled()
    }

    private fun editor(
        draft: () -> ProfileDraft,
        onChange: ((ProfileDraft) -> ProfileDraft) -> Unit = {},
    ) = compose.setContent {
        ProfileEditor(
            draft = draft(),
            errors = emptyMap(),
            canDelete = true,
            message = null,
            actions = ProfileEditorActions(onChange, {}, {}, {}, {}, {}, {}),
        )
    }

    @Test
    fun `the model is a radio list on OpenRouter, with a free-text Other choice`() {
        var draft by mutableStateOf(ProfileDraft.from(openRouter, keySaved = true))
        editor({ draft }) { transform -> draft = transform(draft) }

        compose.onNodeWithText("anthropic/claude-haiku-5.5").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Model id").assertDoesNotExist()
        compose.onNodeWithText("google/gemini-3.8-flash").performScrollTo().performClick()
        assertThat(draft.model).isEqualTo("google/gemini-3.8-flash")

        compose.onNodeWithText("Other model id").performScrollTo().performClick()
        compose.onNodeWithText("Other model id").assertIsSelected()
        compose.onNodeWithText("Model id").performScrollTo().assertExists()
        assertThat(draft.model).isEqualTo("google/gemini-3.8-flash") // kept until the user types
    }

    @Test
    fun `a custom model id opens on Other with the field`() {
        editor({ ProfileDraft.from(openRouter.copy(model = "meta/llama-9"), keySaved = true) })
        compose.onNodeWithText("Other model id").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Model id").performScrollTo().assertExists()
    }

    @Test
    fun `providers without known ids show only the field`() {
        editor({
            ProfileDraft.from(
                ProviderProfile("oa", "OpenAI", ApiFormat.OpenAi, "https://api.openai.com/v1", "gpt-5.5-mini"),
                keySaved = true,
            )
        })
        compose.onNodeWithText("Other model id").assertDoesNotExist()
        compose.onNodeWithText("Model").performScrollTo().assertExists()
    }

    @Test
    fun `model choices per provider`() {
        assertThat(modelChoices(ApiFormat.OpenAi, "https://openrouter.ai/api/v1")).isEqualTo(MODEL_SHORTLIST)
        assertThat(modelChoices(ApiFormat.Anthropic, "https://api.anthropic.com/v1")).containsExactly(ANTHROPIC_HAIKU)
        assertThat(modelChoices(ApiFormat.OpenAi, "https://api.openai.com/v1")).isEmpty()
    }
}
