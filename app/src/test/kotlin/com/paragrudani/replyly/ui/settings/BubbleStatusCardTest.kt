package com.paragrudani.replyly.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import java.time.ZoneId

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
        zone: () -> ZoneId = clock::getZone,
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
                    snooze =
                        BubbleSnoozeInput(
                            until = until,
                            onEnd = { ends++ },
                            clock = clock,
                            is24Hour = false,
                            zone = zone,
                        ),
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

    @Test
    fun `the end time is in the zone read now, not the clock's`() {
        // 21:25 in Kolkata (+05:30) is 15:55 in UTC.
        screen(
            InMemorySettingsRepository(),
            until = clock.instant().plus(Duration.ofMinutes(10)).toEpochMilli(),
            zone = { ZoneId.of("UTC") },
        )
        compose.onNodeWithText("Bubble snoozed until 3:55 PM").assertExists()
    }

    @Test
    fun `after deep sleep the card goes within one step of the wall clock`() {
        // Review r1 N2: the wall clock passes the end while the uptime (the compose clock) stands still.
        compose.mainClock.autoAdvance = false
        screen(InMemorySettingsRepository(), until = clock.instant().plus(Duration.ofMinutes(10)).toEpochMilli())
        compose.mainClock.advanceTimeByFrame()
        clock.advance(Duration.ofMinutes(11))
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("End snooze now").assertExists()

        compose.mainClock.advanceTimeBy(ACTIVE_SNOOZE_STEP_MS)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("End snooze now").assertDoesNotExist()
    }

    @Test
    fun `coming back to the front re-checks the end at once`() {
        val owner = TestLifecycleOwner()
        val until = clock.instant().plus(Duration.ofMinutes(10)).toEpochMilli()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Text(if (rememberActiveSnooze(until, clock) != null) "snoozed" else "back")
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("snoozed").assertExists()

        owner.registry.currentState = Lifecycle.State.STARTED
        clock.advance(Duration.ofMinutes(11))
        owner.registry.currentState = Lifecycle.State.RESUMED
        // The observer writes the state outside a composition: publish it, then draw a frame.
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("back").assertExists()
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
}
