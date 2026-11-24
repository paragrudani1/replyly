package com.paragrudani.replyly.overlay

import androidx.compose.ui.unit.dp
import com.paragrudani.replyly.testing.ScreenshotRule
import com.paragrudani.replyly.testing.ScreenshotTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The snooze card (#56) as it opens above a 300 dp keyboard. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotTest.DEVICE)
class SnoozePickerScreenshotTest {
    @get:Rule val shots = ScreenshotRule()

    @Test
    fun picker() = shots.screen("overlay/snooze_picker") { SnoozePicker(onChoose = {}, onCancel = {}, bottomGap = 300.dp) }
}
