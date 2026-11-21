package com.paragrudani.replyly.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.paragrudani.replyly.cost.CostStatus
import com.paragrudani.replyly.cost.InMemoryCostGuard
import com.paragrudani.replyly.feedback.InMemoryFeedbackLog
import com.paragrudani.replyly.llm.TokenUsage
import com.paragrudani.replyly.settings.InMemoryApiKeyStore
import com.paragrudani.replyly.settings.InMemorySettingsRepository
import com.paragrudani.replyly.settings.ReplylySettings
import com.paragrudani.replyly.testing.MutableClock
import com.paragrudani.replyly.testing.ScreenshotRule
import com.paragrudani.replyly.testing.ScreenshotTest
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
        val keys = InMemoryApiKeyStore().also { runBlocking { it.set("sk-or-test-key") } }
        val cost =
            InMemoryCostGuard(dailyRequestLimit = 200).also { guard ->
                runBlocking {
                    repeat(120) { guard.tryAcquire() }
                    guard.recordUsage(TokenUsage(184_230, 21_544))
                }
            }
        return SettingsViewModel(InMemorySettingsRepository(settings), keys, cost, InMemoryFeedbackLog(clock), clock)
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
        appVersion = "0.1.42",
        listState = LazyListState(firstVisibleItemIndex = firstItem),
    )

    @Test
    fun top() = shots.screen("settings/top") { Screen(viewModel()) }

    /** Scrolled past the end: the list settles on its last items (Limits, Appearance, About). */
    @Test
    fun bottom() = shots.screen("settings/bottom") { Screen(viewModel(), firstItem = 99) }

    @Test
    fun emptyBlocklists() =
        shots.screen("settings/blocklists_empty") {
            Screen(viewModel(ReplylySettings()), firstItem = 3)
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
                WhatsNewContent(
                    title = "What's new in Replyly",
                    notes =
                        listOf(
                            "A fresh look: calmer colours, a new icon and optional Material You colours.",
                            "Settings are grouped into clear sections, with a usage ring for today's requests.",
                            "The feedback log is now a timeline by day, showing which reply you inserted.",
                        ),
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

    @Composable
    private fun UsageInSection(usage: CostStatus) = SettingsSection("Limits and usage") { UsageCard(usage) }

    private companion object {
        val filled =
            ReplylySettings(
                aboutMe = "Engineer in Pune. Short replies, a bit of Hinglish with friends.",
                blockedPackages = setOf("com.google.android.apps.messaging"),
                blockedChatKeys = setOf("com.whatsapp:Bank OTP"),
            )
    }
}
