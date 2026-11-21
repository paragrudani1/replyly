package com.paragrudani.replyly.ui.settings

import com.paragrudani.replyly.cost.InMemoryCostGuard
import com.paragrudani.replyly.feedback.InMemoryFeedbackLog
import com.paragrudani.replyly.settings.ApiFormat
import com.paragrudani.replyly.settings.InMemoryApiKeyStore
import com.paragrudani.replyly.settings.InMemorySettingsRepository
import com.paragrudani.replyly.settings.ProviderProfile
import com.paragrudani.replyly.settings.ReplylySettings
import com.paragrudani.replyly.testing.MutableClock
import com.paragrudani.replyly.testing.ScreenshotRule
import com.paragrudani.replyly.testing.ScreenshotTest
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

    private fun viewModel(): SettingsViewModel {
        val clock = MutableClock.atLocal(LocalDateTime.of(2026, 11, 21, 10, 0))
        val keys = InMemoryApiKeyStore().also { runBlocking { it.set("sk-or-test-key") } }
        val cost = InMemoryCostGuard(dailyRequestLimit = 200)
        return SettingsViewModel(
            InMemorySettingsRepository(ReplylySettings(aboutMe = "Engineer in Pune. Short replies.")),
            keys,
            cost,
            InMemoryFeedbackLog(clock),
            clock,
        )
    }

    private val profiles =
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
                ),
        )

    @Test
    fun main() =
        shots.screen("settings/main") {
            SettingsScreen(
                viewModel(),
                onOpenOnboarding = {},
                onOpenDebug = {},
                onOpenFeedbackLog = {},
                profilesSection = {
                    ProviderProfilesContent(
                        profiles,
                        onSelect = {},
                        onEdit = {},
                        onTest = {},
                        onAdd = {},
                    )
                },
            )
        }
}
