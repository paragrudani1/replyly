package com.paragrudani.replyly.overlay

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.screen.Bounds
import org.junit.Test

class ButtonVisibilityTest {
    private val blocked = mutableSetOf<String>()
    private val visibility = ButtonVisibility(ownPackage = OWN, isAppAllowed = { it !in blocked })

    private val chatWindow = WindowFacts(id = 7, kind = WindowKind.APPLICATION, bounds = SCREEN, active = true)
    private val keyboardWindow = WindowFacts(id = 9, kind = WindowKind.INPUT_METHOD, bounds = KEYBOARD)
    private val input =
        FocusedInput(
            windowId = 7,
            packageName = WHATSAPP,
            editable = true,
            password = false,
            viewId = "com.whatsapp:id/entry",
            bounds = Bounds(30, 1300, 900, 1420),
        )

    private fun decide(
        focused: FocusedInput? = input,
        vararg windows: WindowFacts = arrayOf(chatWindow, keyboardWindow),
    ) = visibility.decide(ScreenFacts(windows.toList(), focused))

    private fun hidden(reason: HideReason) = ButtonDecision.Hide(reason)

    @Test
    fun `shows in a chat app with a focused input and the keyboard open`() {
        assertThat(decide()).isEqualTo(ButtonDecision.Show(input, KEYBOARD))
    }

    @Test
    fun `shows in any app that is not blocked (generic mode)`() {
        val slack = input.copy(packageName = "com.Slack")

        assertThat(decide(slack)).isEqualTo(ButtonDecision.Show(slack, KEYBOARD))
    }

    @Test
    fun `hides when the keyboard is closed`() {
        assertThat(decide(input, chatWindow)).isEqualTo(hidden(HideReason.KEYBOARD_CLOSED))
    }

    @Test
    fun `a zero-height keyboard window counts as closed`() {
        val collapsed = keyboardWindow.copy(bounds = Bounds(0, 2340, 1080, 2340))

        assertThat(decide(input, chatWindow, collapsed)).isEqualTo(hidden(HideReason.KEYBOARD_CLOSED))
    }

    @Test
    fun `hides when nothing has input focus`() {
        assertThat(decide(focused = null)).isEqualTo(hidden(HideReason.NO_INPUT_FOCUS))
    }

    @Test
    fun `hides on a focused field that is not editable`() {
        assertThat(decide(input.copy(editable = false))).isEqualTo(hidden(HideReason.NOT_EDITABLE))
    }

    @Test
    fun `hides on a password field`() {
        assertThat(decide(input.copy(password = true))).isEqualTo(hidden(HideReason.PASSWORD_FIELD))
    }

    @Test
    fun `hides when the focused field's window is not the active app window`() {
        val inactive = chatWindow.copy(active = false)

        assertThat(decide(input, inactive, keyboardWindow)).isEqualTo(hidden(HideReason.WINDOW_NOT_ACTIVE))
    }

    @Test
    fun `a focused but not active app window still counts`() {
        val focusedOnly = chatWindow.copy(active = false, focused = true)

        assertThat(decide(input, focusedOnly, keyboardWindow)).isEqualTo(ButtonDecision.Show(input, KEYBOARD))
    }

    @Test
    fun `hides when the focused field lives in a non-app window, such as the keyboard's own search box`() {
        val inKeyboard = input.copy(windowId = keyboardWindow.id, packageName = "com.google.android.inputmethod.latin")

        assertThat(decide(inKeyboard)).isEqualTo(hidden(HideReason.WINDOW_NOT_ACTIVE))
    }

    @Test
    fun `hides when the focused field's window is gone`() {
        assertThat(decide(input.copy(windowId = 42))).isEqualTo(hidden(HideReason.WINDOW_NOT_ACTIVE))
    }

    @Test
    fun `never shows over Replyly itself or the system UI`() {
        assertThat(decide(input.copy(packageName = OWN))).isEqualTo(hidden(HideReason.EXCLUDED_APP))
        assertThat(decide(input.copy(packageName = "com.android.systemui")))
            .isEqualTo(hidden(HideReason.EXCLUDED_APP))
    }

    @Test
    fun `hides in a blocked app`() {
        blocked += WHATSAPP

        assertThat(decide()).isEqualTo(hidden(HideReason.BLOCKED_APP))
    }

    @Test
    fun `hides when a full-screen keyboard covers the input (landscape extract mode)`() {
        val fullScreenKeyboard = keyboardWindow.copy(bounds = Bounds(0, 0, 2340, 1080))
        val landscapeInput = input.copy(bounds = Bounds(30, 900, 2000, 1020))

        assertThat(decide(landscapeInput, chatWindow, fullScreenKeyboard)).isEqualTo(hidden(HideReason.INPUT_COVERED))
    }

    @Test
    fun `hides when the input has no visible area`() {
        assertThat(decide(input.copy(bounds = Bounds.EMPTY))).isEqualTo(hidden(HideReason.INPUT_COVERED))
    }

    @Test
    fun `a floating keyboard above the input bar does not hide the button`() {
        val floating = keyboardWindow.copy(bounds = Bounds(100, 900, 900, 1500))
        val bottomInput = input.copy(bounds = Bounds(30, 2200, 900, 2320))

        assertThat(
            decide(bottomInput, chatWindow, floating),
        ).isEqualTo(ButtonDecision.Show(bottomInput, floating.bounds))
    }

    @Test
    fun `Show bubble off hides with TURNED_OFF before every other reason`() {
        var off = true
        val turnedOff = ButtonVisibility(ownPackage = OWN, isAppAllowed = { false }, isTurnedOff = { off })
        val password = input.copy(password = true)
        val cases =
            listOf(
                ScreenFacts(listOf(chatWindow, keyboardWindow), input),
                ScreenFacts(listOf(chatWindow), input),
                ScreenFacts(listOf(chatWindow, keyboardWindow), null),
                ScreenFacts(listOf(chatWindow, keyboardWindow), password),
                ScreenFacts(listOf(chatWindow, keyboardWindow), input.copy(packageName = OWN)),
                ScreenFacts(emptyList(), null),
            )

        cases.forEach { assertThat(turnedOff.decide(it)).isEqualTo(hidden(HideReason.TURNED_OFF)) }
        off = false
        assertThat(turnedOff.decide(cases[1])).isEqualTo(hidden(HideReason.KEYBOARD_CLOSED))
    }

    @Test
    fun `the default for Show bubble never hides`() {
        assertThat(decide()).isEqualTo(ButtonDecision.Show(input, KEYBOARD))
        assertThat(decide(input, chatWindow)).isEqualTo(hidden(HideReason.KEYBOARD_CLOSED))
    }

    private companion object {
        const val OWN = "com.paragrudani.replyly"
        const val WHATSAPP = "com.whatsapp"
        val SCREEN = Bounds(0, 0, 1080, 2340)
        val KEYBOARD = Bounds(0, 1440, 1080, 2340)
    }
}
