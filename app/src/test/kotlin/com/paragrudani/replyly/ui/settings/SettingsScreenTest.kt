package com.paragrudani.replyly.ui.settings

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.cost.InMemoryCostGuard
import com.paragrudani.replyly.feedback.InMemoryFeedbackLog
import com.paragrudani.replyly.settings.InMemoryApiKeyStore
import com.paragrudani.replyly.settings.InMemorySettingsRepository
import com.paragrudani.replyly.settings.current
import com.paragrudani.replyly.testing.MutableClock
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val settings = InMemorySettingsRepository()
    private val clock = MutableClock.atLocal(LocalDateTime.of(2026, 11, 21, 10, 0))
    private val vm =
        SettingsViewModel(settings, InMemoryApiKeyStore(), InMemoryCostGuard(), InMemoryFeedbackLog(clock), clock)

    @Test
    fun `long-pressing the title opens the debug screen and the wordmark reads as Replyly`() {
        var debugOpened = 0
        compose.setContent {
            ReplylyTheme(dynamicColor = false) {
                SettingsScreen(vm, onOpenOnboarding = {}, onOpenDebug = { debugOpened++ }, onOpenFeedbackLog = {})
            }
        }
        compose.onNodeWithContentDescription("Replyly", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(SETTINGS_TITLE_TAG).performTouchInput { longClick() }
        assertThat(debugOpened).isEqualTo(1)
    }

    @Test
    fun `the Material You switch saves at once`() {
        compose.setContent {
            ReplylyTheme(dynamicColor = false) {
                SettingsScreen(vm, onOpenOnboarding = {}, onOpenDebug = {}, onOpenFeedbackLog = {})
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Material You colours"))
        compose.onNodeWithText("Material You colours").performClick()
        compose.waitForIdle()
        assertThat(runBlocking { settings.current().useDynamicColor }).isTrue()
    }
}
