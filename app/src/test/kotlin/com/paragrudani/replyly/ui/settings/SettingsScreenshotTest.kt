package com.paragrudani.replyly.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.paragrudani.replyly.R
import com.paragrudani.replyly.cost.CostStatus
import com.paragrudani.replyly.cost.InMemoryCostGuard
import com.paragrudani.replyly.feedback.InMemoryFeedbackLog
import com.paragrudani.replyly.llm.TokenUsage
import com.paragrudani.replyly.settings.ApiFormat
import com.paragrudani.replyly.settings.InMemorySettingsRepository
import com.paragrudani.replyly.settings.ProviderProfile
import com.paragrudani.replyly.settings.ReplylySettings
import com.paragrudani.replyly.testing.MutableClock
import com.paragrudani.replyly.testing.ScreenshotRule
import com.paragrudani.replyly.testing.ScreenshotTest
import com.paragrudani.replyly.ui.settings.profiles.MODEL_SHORTLIST
import com.paragrudani.replyly.ui.settings.profiles.ProfileDraft
import com.paragrudani.replyly.ui.settings.profiles.ProfileEditor
import com.paragrudani.replyly.ui.settings.profiles.ProfileEditorActions
import com.paragrudani.replyly.ui.settings.profiles.ProfileRow
import com.paragrudani.replyly.ui.settings.profiles.ProfilesUiState
import com.paragrudani.replyly.ui.settings.profiles.ProviderProfilesContent
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotTest.DEVICE)
class SettingsScreenshotTest {
    @get:Rule val shots = ScreenshotRule()

    private fun viewModel(settings: ReplylySettings = filled): SettingsViewModel {
        val clock = MutableClock.atLocal(LocalDateTime.of(2026, 11, 21, 10, 0))
        val cost =
            InMemoryCostGuard(dailyRequestLimit = 200).also { guard ->
                runBlocking {
                    repeat(120) { guard.tryAcquire() }
                    guard.recordUsage(TokenUsage(184_230, 21_544))
                }
            }
        return SettingsViewModel(InMemorySettingsRepository(settings), cost, InMemoryFeedbackLog(clock), clock)
    }

    @Composable
    private fun Screen(
        vm: SettingsViewModel,
        firstItem: Int = 0,
    ) = SettingsScreen(
        vm,
        onOpenOnboarding = {},
        onOpenDebug = {},
        onOpenFeedbackLog = {},
        aiProvider = {
            SettingsGroupPadding {
                ProviderProfilesContent(profiles, onSelect = {}, onEdit = {}, onTest = {}, onAdd = {})
            }
        },
        onOpenWhatsNew = {},
        appVersion = "0.1.42",
        listState = LazyListState(firstVisibleItemIndex = firstItem),
    )

    @Test
    fun top() = shots.screen("settings/top") { Screen(viewModel()) }

    /** Scrolled past the end: the list settles on its last items (Limits, Appearance, About). */
    @Test
    fun bottom() = shots.screen("settings/bottom") { Screen(viewModel(), firstItem = 99) }

    /** "Your style", with "Names people call me" filled in; "On X" (issue #30) starts below it. */
    @Test
    fun yourStyle() =
        shots.screen("settings/your_style") {
            Screen(viewModel(filled.copy(myNames = "Kiran, Kiru", myXHandle = "kiran_test")), firstItem = 2)
        }

    /** "On X" (issue #30): My X handle, My X voice and the longer-posts switch on. */
    @Test
    fun onX() =
        shots.screen("settings/on_x") {
            Screen(
                viewModel(
                    filled.copy(
                        myXHandle = "kiran_test",
                        myXVoice = "Indie Android dev. Dry humour, short sentences, rarely emoji, never hashtags.",
                        xPostLength = ReplylySettings.X_POST_MEDIUM,
                    ),
                ),
                firstItem = 3,
            )
        }

    @Test
    fun emptyBlocklists() =
        shots.screen("settings/blocklists_empty") {
            Screen(viewModel(ReplylySettings()), firstItem = 4)
        }

    @Test
    fun usage0() = shots.component("settings/usage_0") { UsageInSection(CostStatus(0, 200, 0, 0)) }

    @Test
    fun usage60() = shots.component("settings/usage_60") { UsageInSection(CostStatus(120, 200, 184_230, 21_544)) }

    @Test
    fun usage100() = shots.component("settings/usage_100") { UsageInSection(CostStatus(200, 200, 412_002, 50_310)) }

    @Test
    fun whatsNew() =
        shots.component("settings/whats_new") {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
                // The shipped copy, so the shot changes when the notes do.
                WhatsNewContent(
                    title = stringResource(R.string.whats_new_title),
                    notes = stringArrayResource(R.array.whats_new_notes).toList(),
                    onDone = {},
                    modifier =
                        androidx.compose.ui.Modifier
                            .padding(top = com.paragrudani.replyly.ui.theme.Spacing.XL),
                )
            }
        }

    @Test
    fun modelPicker() =
        shots.component("settings/model_picker") {
            SettingsSection("Model") {
                ModelPickerList(models = MODEL_SHORTLIST, selected = "google/gemini-3.8-flash", onSelect = {})
            }
        }

    /** The editor's model field as a radio list, OpenRouter shortlist plus "Other model id". */
    @Test
    fun profileEditor() =
        shots.screen("settings/profile_editor") {
            ProfileEditor(
                draft = ProfileDraft.from(profiles.rows.first().profile, keySaved = true),
                errors = emptyMap(),
                canDelete = true,
                message = null,
                actions = ProfileEditorActions({}, {}, {}, {}, {}, {}, {}),
            )
        }

    @Composable
    private fun UsageInSection(usage: CostStatus) = SettingsSection("Limits and usage") { UsageCard(usage) }

    private companion object {
        /** Fixed provider profiles: the real section reads encrypted storage. */
        val profiles =
            ProfilesUiState(
                loaded = true,
                rows =
                    listOf(
                        ProfileRow(
                            ProviderProfile(
                                "or",
                                "OpenRouter",
                                ApiFormat.OpenAi,
                                ReplylySettings.DEFAULT_ENDPOINT,
                                ReplylySettings.DEFAULT_MODEL,
                            ),
                            active = true,
                            hasKey = true,
                        ),
                        ProfileRow(
                            ProviderProfile(
                                "an",
                                "Anthropic",
                                ApiFormat.Anthropic,
                                "https://api.anthropic.com/v1",
                                "claude-haiku-5-5",
                            ),
                            active = false,
                            hasKey = false,
                        ),
                    ),
            )

        val filled =
            ReplylySettings(
                aboutMe = "Engineer in Pune. Short replies, a bit of Hinglish with friends.",
                blockedPackages = setOf("com.google.android.apps.messaging"),
                blockedChatKeys = setOf("com.whatsapp:Bank OTP"),
            )
    }
}
