package com.paragrudani.replyly.overlay

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.insert.InputFieldRef
import com.paragrudani.replyly.panel.PanelRequest
import com.paragrudani.replyly.parser.ParsedChat
import com.paragrudani.replyly.policy.BlockReason
import com.paragrudani.replyly.screen.Bounds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OverlayControllerTest {
    private class FakeUi : OverlayUi {
        var button: ButtonPosition? = null
        var buttonShows = 0
        var panel: PanelRequest? = null
        val notices = mutableListOf<Notice>()
        val busy = mutableListOf<Boolean>()

        override fun showButton(slot: ButtonSlot) {
            buttonShows++
            button = slot.position
        }

        override fun hideButton() {
            button = null
        }

        override fun openPanel(request: PanelRequest) {
            panel = request
        }

        override fun closePanel() {
            panel = null
        }

        override fun showNotice(notice: Notice) {
            notices += notice
        }

        override fun setButtonBusy(busy: Boolean) {
            this.busy += busy
        }
    }

    private val ui = FakeUi()
    private val input = FocusedInput(7, WHATSAPP, true, false, "com.whatsapp:id/entry", Bounds(30, 1300, 900, 1420))
    private val keyboard = WindowFacts(9, WindowKind.INPUT_METHOD, Bounds(0, 1440, 1080, 2340))
    private val chatWindow = WindowFacts(7, WindowKind.APPLICATION, Bounds(0, 0, 1080, 2340), active = true)
    private var facts = ScreenFacts(listOf(chatWindow, keyboard), input)
    private var factReads = 0
    private var tapAnswer: suspend () -> TapOutcome = { TapOutcome.Open(CHAT) }
    private val remembered = mutableListOf<InputFieldRef>()
    private var taps = 0
    private val savedY = mutableMapOf<String, Int>()

    private fun TestScope.controller() =
        OverlayController(
            readFacts = {
                factReads++
                facts
            },
            visibility = ButtonVisibility(ownPackage = "com.paragrudani.replyly", isAppAllowed = { true }),
            place = { shown ->
                val y = savedY[shown.input.packageName] ?: shown.keyboard.top
                ButtonSlot(ButtonPosition(shown.keyboard.right, y), minY = 100, maxY = shown.keyboard.top)
            },
            ui = ui,
            tap = {
                taps++
                tapAnswer()
            },
            rememberTarget = { remembered += it },
            scope = this,
            rememberPosition = { shown, y -> savedY[shown.input.packageName] = y },
        )

    @Test
    fun `a window change shows the button where placement says`() =
        runTest {
            val controller = controller()

            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()

            assertThat(ui.button).isEqualTo(ButtonPosition(1080, 1440))
        }

    @Test
    fun `a burst of events reads the screen once`() =
        runTest {
            val controller = controller()

            repeat(5) { controller.onEvent(OverlayEvent.WINDOWS) }
            advanceUntilIdle()

            assertThat(factReads).isEqualTo(1)
        }

    @Test
    fun `hides as soon as the keyboard closes`() =
        runTest {
            val controller = controller()
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()

            facts = facts.copy(windows = listOf(chatWindow))
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()

            assertThat(ui.button).isNull()
        }

    @Test
    fun `content changes are ignored while the button is hidden`() =
        runTest {
            val controller = controller()

            controller.onEvent(OverlayEvent.CONTENT)
            advanceUntilIdle()

            assertThat(factReads).isEqualTo(0)
        }

    @Test
    fun `content changes re-check while the button is shown`() =
        runTest {
            val controller = controller()
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()

            facts = facts.copy(focusedInput = null)
            controller.onEvent(OverlayEvent.CONTENT, WHATSAPP)
            advanceUntilIdle()

            assertThat(ui.button).isNull()
        }

    @Test
    fun `content changes from another app (the keyboard) are ignored`() =
        runTest {
            val controller = controller()
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()
            val reads = factReads

            controller.onEvent(OverlayEvent.CONTENT, "com.google.android.inputmethod.latin")
            controller.onEvent(OverlayEvent.CONTENT, null)
            advanceUntilIdle()

            assertThat(factReads).isEqualTo(reads)
        }

    @Test
    fun `screen off hides the button`() =
        runTest {
            val controller = controller()
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()

            controller.onScreenOff()

            assertThat(ui.button).isNull()
        }

    @Test
    fun `screen off closes the panel`() =
        runTest {
            val controller = openPanel()

            controller.onScreenOff()

            assertThat(ui.panel).isNull()
        }

    @Test
    fun `screen off during a tap opens nothing`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            tapAnswer = {
                gate.await()
                TapOutcome.Open(CHAT)
            }
            val controller = controller()
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()
            controller.onButtonTapped()
            advanceUntilIdle()

            controller.onScreenOff()
            gate.complete(Unit)
            advanceUntilIdle()

            assertThat(ui.panel).isNull()
            assertThat(remembered).isEmpty()
        }

    @Test
    fun `the panel closes when the chat's window is gone (Home, Recents, another app)`() =
        runTest {
            val controller = openPanel()

            facts = ScreenFacts(listOf(WindowFacts(3, WindowKind.APPLICATION, chatWindow.bounds, active = true)), null)
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()

            assertThat(ui.panel).isNull()
        }

    @Test
    fun `a focus-only change keeps the panel open`() =
        runTest {
            val controller = openPanel()

            // The panel took focus: the chat window is neither active nor focused, its input lost
            // focus and the keyboard closed. The window itself is still there.
            facts = ScreenFacts(listOf(chatWindow.copy(active = false, focused = false)), focusedInput = null)
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()

            assertThat(ui.panel).isEqualTo(PanelRequest(CHAT))
        }

    @Test
    fun `the panel does not open if the chat's window went away during the tap`() =
        runTest {
            tapAnswer = {
                facts = ScreenFacts(listOf(keyboard), null)
                TapOutcome.Open(CHAT)
            }
            val controller = controller()
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()

            controller.onButtonTapped()
            advanceUntilIdle()

            assertThat(ui.panel).isNull()
            assertThat(remembered).isEmpty()
        }

    @Test
    fun `the button is dimmed while a tap is in flight`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            tapAnswer = {
                gate.await()
                TapOutcome.NotAChat
            }
            val controller = controller()
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()

            controller.onButtonTapped()
            advanceUntilIdle()
            assertThat(ui.busy).containsExactly(true)

            gate.complete(Unit)
            advanceUntilIdle()
            assertThat(ui.busy).containsExactly(true, false).inOrder()
        }

    @Test
    fun `tap on an allowed chat remembers the input field and opens the panel`() =
        runTest {
            val controller = controller()
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()

            controller.onButtonTapped()
            advanceUntilIdle()

            assertThat(ui.panel).isEqualTo(PanelRequest(CHAT))
            assertThat(remembered).containsExactly(
                InputFieldRef(7, WHATSAPP, "com.whatsapp:id/entry", Bounds(30, 1300, 900, 1420)),
            )
        }

    @Test
    fun `the button stays hidden while the panel is open, and comes back after it closes`() =
        runTest {
            val controller = controller()
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()
            controller.onButtonTapped()
            advanceUntilIdle()

            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()
            assertThat(ui.button).isNull()

            controller.onPanelClosed()
            advanceUntilIdle()
            assertThat(ui.panel).isNull()
            assertThat(ui.button).isEqualTo(ButtonPosition(1080, 1440))
        }

    @Test
    fun `tap on a blocked screen shows the notice, opens nothing and remembers nothing`() =
        runTest {
            tapAnswer = { TapOutcome.Blocked(BlockReason.BANKING_APP) }
            val controller = controller()
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()

            controller.onButtonTapped()
            advanceUntilIdle()

            assertThat(ui.notices).containsExactly(Notice.OFF_HERE)
            assertThat(ui.panel).isNull()
            assertThat(remembered).isEmpty()
            assertThat(ui.button).isNotNull()
        }

    @Test
    fun `tap where no chat is found shows the cannot-read notice`() =
        runTest {
            tapAnswer = { TapOutcome.NotAChat }
            val controller = controller()
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()

            controller.onButtonTapped()
            advanceUntilIdle()

            assertThat(ui.notices).containsExactly(Notice.NO_CHAT)
        }

    @Test
    fun `a second tap while the first is still reading is ignored`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var taps = 0
            tapAnswer = {
                taps++
                gate.await()
                TapOutcome.Open(CHAT)
            }
            val controller = controller()
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()

            controller.onButtonTapped()
            controller.onButtonTapped()
            gate.complete(Unit)
            advanceUntilIdle()

            assertThat(taps).isEqualTo(1)
        }

    @Test
    fun `a tap with the button hidden does nothing`() =
        runTest {
            val controller = controller()

            controller.onButtonTapped()
            advanceUntilIdle()

            assertThat(ui.panel).isNull()
            assertThat(ui.notices).isEmpty()
        }

    @Test
    fun `the same position is not pushed to the window again`() =
        runTest {
            val controller = controller()
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()

            assertThat(ui.buttonShows).isEqualTo(1)
        }

    @Test
    fun `stop hides everything`() =
        runTest {
            val controller = controller()
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()
            controller.onButtonTapped()
            advanceUntilIdle()

            controller.stop()

            assertThat(ui.button).isNull()
            assertThat(ui.panel).isNull()
        }

    @Test
    fun `a drag remembers the spot for the app and never taps`() =
        runTest {
            val controller = controller()
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()

            controller.onButtonMoved(900)
            advanceUntilIdle()

            assertThat(savedY).containsExactly(WHATSAPP, 900)
            assertThat(taps).isEqualTo(0)
            assertThat(ui.busy).isEmpty()
            assertThat(ui.panel).isNull()
        }

    @Test
    fun `a drag is clamped to the slot`() =
        runTest {
            val controller = controller()
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()

            controller.onButtonMoved(-50)

            assertThat(savedY).containsExactly(WHATSAPP, 100)
        }

    @Test
    fun `after a drag the next refresh keeps the button where it was dropped`() =
        runTest {
            val controller = controller()
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()
            val shows = ui.buttonShows

            controller.onButtonMoved(900)
            controller.onEvent(OverlayEvent.WINDOWS)
            advanceUntilIdle()

            // The slot from placement now matches what the window already shows: no re-show.
            assertThat(ui.buttonShows).isEqualTo(shows)
        }

    @Test
    fun `a move while the button is hidden is ignored`() =
        runTest {
            val controller = controller()

            controller.onButtonMoved(900)

            assertThat(savedY).isEmpty()
        }

    private suspend fun TestScope.openPanel(): OverlayController {
        val controller = controller()
        controller.onEvent(OverlayEvent.WINDOWS)
        advanceUntilIdle()
        controller.onButtonTapped()
        advanceUntilIdle()
        assertThat(ui.panel).isEqualTo(PanelRequest(CHAT))
        return controller
    }

    private companion object {
        const val WHATSAPP = "com.whatsapp"
        val CHAT = ParsedChat(WHATSAPP, "Asha", isGroup = false, messages = emptyList(), historyApplies = true)
    }
}
