package com.paragrudani.replyly.overlay

import android.content.Context
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.snooze.SnoozeChoice
import com.paragrudani.replyly.testing.ScreenshotTest
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

/** The snooze card (#56): its four rows, cancel paths, and the window's choose path. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = ScreenshotTest.DEVICE)
class SnoozePickerTest {
    @get:Rule val compose = createComposeRule()

    private val chosen = mutableListOf<SnoozeChoice>()
    private var cancels = 0

    private fun show() =
        compose.setContent {
            ReplylyTheme(dynamicColor = false, reducedMotion = true) {
                SnoozePicker(onChoose = { chosen += it }, onCancel = { cancels++ }, bottomGap = 300.dp)
            }
        }

    @Test
    fun `shows the four options as 48 dp rows that say what a tap does`() {
        show()
        listOf("10 minutes", "1 hour", "Until tomorrow (6 AM)", "Until I turn it back on").forEach { label ->
            compose
                .onNodeWithText(label)
                .assertHeightIsAtLeast(48.dp)
                .assert(
                    SemanticsMatcher("click label Snooze") {
                        it.config.getOrNull(SemanticsActions.OnClick)?.label ==
                            "Snooze"
                    },
                )
        }
        compose.onAllNodesWithText("Snooze the bubble").assertCountEquals(1)
    }

    @Test
    fun `each row chooses its option`() {
        show()
        compose.onNodeWithText("10 minutes").performClick()
        compose.onNodeWithText("1 hour").performClick()
        compose.onNodeWithText("Until tomorrow (6 AM)").performClick()
        compose.onNodeWithText("Until I turn it back on").performClick()
        assertThat(chosen).containsExactlyElementsIn(SnoozeChoice.entries).inOrder()
        assertThat(cancels).isEqualTo(0)
    }

    @Test
    fun `a tap outside the card cancels`() {
        show()
        compose.onNodeWithTag(SNOOZE_OUTSIDE_TAG).performTouchInput { click(topCenter.copy(y = 40f)) }
        assertThat(cancels).isEqualTo(1)
        assertThat(chosen).isEmpty()
    }

    @Test
    fun `a tap on the card's empty space does not cancel`() {
        // On the title line, right of the title, clear of the rounded corner (a tap there is outside).
        show()
        compose.onNodeWithTag(SNOOZE_CARD_TAG).performTouchInput { click(Offset(right - 60f, 48f)) }
        assertThat(cancels).isEqualTo(0)
    }

    @Test
    fun `Cancel cancels`() {
        show()
        compose.onNodeWithText("Cancel").performClick()
        assertThat(cancels).isEqualTo(1)
    }

    @Test
    fun `the window stops at the nav bar, is titled and not focusable, and a choice stores, closes, toasts`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        var stored: SnoozeChoice? = null
        val window =
            SnoozePickerWindow(context) { choice ->
                stored = choice
                "Snoozed for 1 hour. Shake your phone to bring it back."
            }
        window.open(keyboardTop = 1400)
        val params = window.root!!.layoutParams as WindowManager.LayoutParams
        assertThat(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isNotEqualTo(0)
        assertThat(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isEqualTo(0)
        // Robolectric has no navigation bar inset: the whole screen.
        val bounds = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        assertThat(params.width).isEqualTo(bounds.width())
        assertThat(params.height).isEqualTo(bounds.height())
        assertThat(params.type).isEqualTo(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        assertThat(params.title.toString()).isEqualTo("Snooze the bubble")

        window.choose(SnoozeChoice.ONE_HOUR)
        ShadowLooper.idleMainLooper()

        assertThat(stored).isEqualTo(SnoozeChoice.ONE_HOUR)
        assertThat(window.isOpen).isFalse()
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo("Snoozed for 1 hour. Shake your phone to bring it back.")
    }

    @Test
    fun `close removes the window, and a choice after close does nothing`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        var stores = 0
        val window =
            SnoozePickerWindow(context) {
                stores++
                ""
            }
        window.open(keyboardTop = 1400)
        window.close()
        window.choose(SnoozeChoice.TEN_MINUTES)
        assertThat(window.isOpen).isFalse()
        assertThat(stores).isEqualTo(0)
    }

    @Test
    fun `the layer stops at a 3-button navigation bar, at the bottom or the side`() {
        // Portrait: a 48 px bar at the bottom; the keyboard's top is 1,400 of 2,340.
        assertThat(PickerLayer.of(1080, 2340, navLeft = 0, navRight = 0, navBottom = 48, keyboardTop = 1400))
            .isEqualTo(PickerLayer(x = 0, width = 1080, height = 2292, bottomGapPx = 892))
        // Landscape: the bar on the right.
        assertThat(PickerLayer.of(2340, 1080, navLeft = 0, navRight = 126, navBottom = 0, keyboardTop = 600))
            .isEqualTo(PickerLayer(x = 0, width = 2214, height = 1080, bottomGapPx = 480))
        // Seascape: on the left.
        assertThat(PickerLayer.of(2340, 1080, navLeft = 126, navRight = 0, navBottom = 0, keyboardTop = 600).x)
            .isEqualTo(126)
        // No keyboard below the layer's bottom: no gap.
        assertThat(PickerLayer.of(1080, 2340, 0, 0, 48, keyboardTop = 2340).bottomGapPx).isEqualTo(0)
    }
}
