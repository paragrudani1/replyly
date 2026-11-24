package com.paragrudani.replyly.ui.settings

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.cost.InMemoryCostGuard
import com.paragrudani.replyly.feedback.InMemoryFeedbackLog
import com.paragrudani.replyly.settings.InMemorySettingsRepository
import com.paragrudani.replyly.settings.ReplylySettings
import com.paragrudani.replyly.settings.current
import com.paragrudani.replyly.testing.MutableClock
import com.paragrudani.replyly.testing.ScreenshotTest
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.LocalDateTime

/** The status card at the top of Settings (#56): each variant and its button. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = ScreenshotTest.DEVICE)
class BubbleStatusCardTest {
    @get:Rule val compose = createComposeRule()

    private val clock = MutableClock.atLocal(LocalDateTime.of(2026, 11, 24, 21, 15))
    private val nowMillis get() = clock.millis()
    private var ends = 0

    private fun screen(
        settings: InMemorySettingsRepository,
        until: Long?,
    ): SettingsViewModel {
        val vm = SettingsViewModel(settings, InMemoryCostGuard(), InMemoryFeedbackLog(clock), clock)
        compose.setContent {
            ReplylyTheme(dynamicColor = false) {
                SettingsScreen(
                    vm,
                    onOpenOnboarding = {},
                    onOpenDebug = {},
                    onOpenFeedbackLog = {},
                    aiProvider = {},
                    snooze = BubbleSnoozeInput(until = until, onEnd = { ends++ }, clock = clock, is24Hour = false),
                )
            }
        }
        return vm
    }

    private val politeLiveRegion =
        SemanticsMatcher("polite live region") {
            it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite
        }

    @Test
    fun `no card when shown and not snoozed`() {
        screen(InMemorySettingsRepository(), until = null)
        compose.onNodeWithText("Bubble is off").assertDoesNotExist()
        compose.onNodeWithText("End snooze now").assertDoesNotExist()
    }

    @Test
    fun `no card for a snooze that already ended`() {
        screen(InMemorySettingsRepository(), until = nowMillis - 1)
        compose.onNodeWithText("End snooze now").assertDoesNotExist()
    }

    @Test
    fun `off shows Bubble is off, and Turn back on saves the switch`() {
        val settings = InMemorySettingsRepository(ReplylySettings(showBubble = false))
        screen(settings, until = null)

        compose.onNodeWithText("Bubble is off").assert(politeLiveRegion)
        compose.onNodeWithText("Turn back on").assertHasClickAction().performClick()
        compose.waitForIdle()

        assertThat(runBlocking { settings.current().showBubble }).isTrue()
        compose.onNodeWithText("Bubble is off").assertDoesNotExist()
    }

    @Test
    fun `snoozed shows the end time, and End snooze now ends it`() {
        screen(InMemorySettingsRepository(), until = clock.instant().plus(Duration.ofMinutes(10)).toEpochMilli())

        compose.onNodeWithText("Bubble snoozed until 9:25 PM").assert(politeLiveRegion)
        compose.onNodeWithText("End snooze now").assertHasClickAction().performClick()
        assertThat(ends).isEqualTo(1)
    }

    @Test
    fun `until turned back on says so`() {
        screen(InMemorySettingsRepository(), until = Long.MAX_VALUE)
        compose.onNodeWithText("Bubble snoozed until you turn it back on").assertExists()
    }

    @Test
    fun `off wins over snoozed, and the snooze card follows Turn back on`() {
        val settings = InMemorySettingsRepository(ReplylySettings(showBubble = false))
        screen(settings, until = Long.MAX_VALUE)

        compose.onNodeWithText("Bubble is off").assertExists()
        compose.onNodeWithText("End snooze now").assertDoesNotExist()
        compose.onNodeWithText("Turn back on").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Bubble snoozed until you turn it back on").assertExists()
    }

    @Test
    fun `the card goes away by itself when the snooze ends`() {
        compose.mainClock.autoAdvance = false
        screen(InMemorySettingsRepository(), until = clock.instant().plus(Duration.ofMinutes(10)).toEpochMilli())
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("End snooze now").assertExists()

        clock.advance(Duration.ofMinutes(10))
        compose.mainClock.advanceTimeBy(10 * 60_000L)
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText("End snooze now").assertDoesNotExist()
    }
}
