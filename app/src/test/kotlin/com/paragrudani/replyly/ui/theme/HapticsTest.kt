package com.paragrudani.replyly.ui.theme

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class HapticsTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Records what the helper asks the system to play. */
    private class RecordingView(
        context: Context,
    ) : View(context) {
        val played = mutableListOf<Int>()

        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            played += feedbackConstant
            return true
        }
    }

    private fun setSystemTouchFeedback(on: Boolean) {
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, if (on) 1 else 0)
    }

    @After fun resetSetting() = setSystemTouchFeedback(true)

    @Test
    fun `each event maps to its one feedback type`() {
        val expected =
            mapOf(
                HapticEvent.Tap to HapticFeedbackConstants.VIRTUAL_KEY,
                HapticEvent.CardInsert to HapticFeedbackConstants.CONFIRM,
                HapticEvent.LongPressCopy to HapticFeedbackConstants.LONG_PRESS,
                HapticEvent.DragSnap to HapticFeedbackConstants.SEGMENT_TICK,
                HapticEvent.ToggleOn to HapticFeedbackConstants.TOGGLE_ON,
                HapticEvent.ToggleOff to HapticFeedbackConstants.TOGGLE_OFF,
                HapticEvent.Save to HapticFeedbackConstants.CONFIRM,
                HapticEvent.SnoozeEnd to HapticFeedbackConstants.CONFIRM,
            )
        // A new event must get a deliberate mapping (and a row in DESIGN.md > Haptics).
        assertThat(HapticEvent.entries.toSet()).isEqualTo(expected.keys)
        HapticEvent.entries.forEach { assertThat(it.feedbackConstant).isEqualTo(expected.getValue(it)) }
    }

    @Test
    fun `only the snooze end plays without a view, as the predefined click`() {
        assertThat(HapticEvent.entries.filter { it.predefinedEffect != null }).containsExactly(HapticEvent.SnoozeEnd)
        assertThat(HapticEvent.SnoozeEnd.predefinedEffect).isEqualTo(VibrationEffect.EFFECT_CLICK)
    }

    @Test
    fun `context entry point plays the predefined effect on the system vibrator`() {
        assertThat(context.performReplylyHaptic(HapticEvent.SnoozeEnd)).isTrue()
        assertThat(shadowOf(vibrator()).effectId).isEqualTo(VibrationEffect.EFFECT_CLICK)
    }

    @Test
    fun `context entry point plays nothing when system touch feedback is off`() {
        setSystemTouchFeedback(false)
        assertThat(context.performReplylyHaptic(HapticEvent.SnoozeEnd)).isFalse()
        assertThat(shadowOf(vibrator()).isVibrating).isFalse()
    }

    @Test
    fun `context entry point ignores events that need a view`() {
        assertThat(context.performReplylyHaptic(HapticEvent.Tap)).isFalse()
        assertThat(shadowOf(vibrator()).isVibrating).isFalse()
    }

    private fun vibrator(): Vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator

    @Test
    fun `toggle picks on or off`() {
        assertThat(HapticEvent.toggle(true)).isEqualTo(HapticEvent.ToggleOn)
        assertThat(HapticEvent.toggle(false)).isEqualTo(HapticEvent.ToggleOff)
    }

    @Test
    fun `view entry point plays the mapped constant`() {
        val view = RecordingView(context)
        assertThat(view.performReplylyHaptic(HapticEvent.LongPressCopy)).isTrue()
        assertThat(view.played).containsExactly(HapticFeedbackConstants.LONG_PRESS)
    }

    @Test
    fun `nothing plays when system touch feedback is off`() {
        setSystemTouchFeedback(false)
        val view = RecordingView(context)
        assertThat(view.performReplylyHaptic(HapticEvent.Tap)).isFalse()
        assertThat(view.played).isEmpty()
    }

    @Test
    fun `nothing plays when the view disabled haptics`() {
        val view = RecordingView(context).apply { isHapticFeedbackEnabled = false }
        assertThat(ReplylyHaptics(view).perform(HapticEvent.Save)).isFalse()
        assertThat(view.played).isEmpty()
    }

    @Test
    fun `compose entry point binds to the current view`() {
        var haptics: ReplylyHaptics? = null
        compose.setContent { haptics = rememberReplylyHaptics() }
        compose.runOnIdle { haptics!!.perform(HapticEvent.CardInsert) }
        assertThat(haptics).isNotNull()
    }
}
