package com.paragrudani.replyly.panel

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** With the keyboard up the window is short: the sheet must stay below the status bar. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-mdpi")
class PanelSheetInsetsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `a tall sheet with the keyboard up shrinks below the status bar instead of sliding under it`() {
        compose.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false) }
        compose.setContent {
            ReplylyTheme(darkTheme = false, dynamicColor = false, reducedMotion = true) {
                PanelSheet(PanelSheetState(), onClosed = {}) {
                    Box(Modifier.fillMaxWidth().height(480.dp).testTag("content")) { Text("Sheet content") }
                }
            }
        }
        compose.runOnUiThread {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_BAR, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 24))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, KEYBOARD))
                    .setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build()
            compose.activity.window.decorView
                .dispatchApplyWindowInsets(checkNotNull(insets.toWindowInsets()))
        }
        compose.waitForIdle()

        val bounds = compose.onNodeWithTag("content").getBoundsInRoot()
        // mdpi: 1 dp = 1 px. 780 - 340 (keyboard) - 480 (content) would put the top at -40.
        assertThat(bounds.top.value).isAtLeast(STATUS_BAR.toFloat())
        assertThat(bounds.bottom.value).isAtMost(780f - KEYBOARD)
    }

    private companion object {
        const val STATUS_BAR = 40
        const val KEYBOARD = 340
    }
}
