package com.paragrudani.replyly.panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp")
class PanelSheetTest {
    @get:Rule val compose = createComposeRule()

    private val sheet = PanelSheetState()
    private var closed = 0

    private fun show(reducedMotion: Boolean) {
        compose.setContent {
            ReplylyTheme(darkTheme = false, dynamicColor = false, reducedMotion = reducedMotion) {
                PanelSheet(sheet, onClosed = { closed++ }) {
                    Box(Modifier.fillMaxWidth().height(300.dp)) { Text("Sheet content") }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a close request slides the sheet out, then reports closed once`() {
        show(reducedMotion = false)
        val start = compose.mainClock.currentTime

        sheet.requestClose()
        sheet.requestClose()
        assertThat(closed).isEqualTo(0)
        compose.waitForIdle()

        assertThat(closed).isEqualTo(1)
        // The slide-down took frames (250 ms), it did not snap.
        assertThat(compose.mainClock.currentTime - start).isAtLeast(200L)
    }

    @Test
    fun `with reduced motion it closes at once`() {
        show(reducedMotion = true)

        sheet.requestClose()
        compose.waitForIdle()

        assertThat(closed).isEqualTo(1)
    }

    @Test
    fun `a tap on the backdrop closes`() {
        show(reducedMotion = true)

        compose.onRoot().performTouchInput { click(Offset(centerX, 20f)) }
        compose.waitForIdle()

        assertThat(sheet.closeRequested).isTrue()
        assertThat(closed).isEqualTo(1)
    }

    @Test
    fun `a swipe down on the sheet closes it`() {
        show(reducedMotion = true)

        compose.onNodeWithText("Sheet content").performTouchInput { swipeDown(startY = top, endY = bottom + 400f) }
        compose.waitForIdle()

        assertThat(closed).isEqualTo(1)
    }

    @Test
    fun `a tap on the sheet does not close`() {
        show(reducedMotion = true)

        compose.onNodeWithText("Sheet content").performTouchInput { click(center) }
        compose.waitForIdle()

        assertThat(closed).isEqualTo(0)
    }
}
