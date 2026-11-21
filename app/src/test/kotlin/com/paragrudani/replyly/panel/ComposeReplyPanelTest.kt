package com.paragrudani.replyly.panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.llm.SuggestionResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ComposeReplyPanelTest {
    @get:Rule
    val compose = createComposeRule()

    private val generator = FakeGenerator()
    private val feedback = FakeFeedbackLog()
    private val panel =
        ComposeReplyPanel(
            ReplyPanelDependencies(
                assembler = FakeAssembler(),
                generator = generator,
                inserter = FakeInserter(),
                feedbackLog = feedback,
                contactNotes = FakeNoteStore(),
                history = FakeHistory(),
            ),
        )

    @Test
    fun `a new or equal request while the panel is open makes no second LLM call`() {
        var request by mutableStateOf(PanelRequest(whatsAppChat))
        compose.setContent { panel.Content(request = request, onClose = {}) }
        compose.waitForIdle()
        assertThat(generator.calls).isEqualTo(1)

        request = PanelRequest(whatsAppChat.copy())
        compose.waitForIdle()
        request = PanelRequest(whatsAppChat.copy(messages = whatsAppChat.messages + whatsAppChat.messages))
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(FakeGenerator.GENERATE_MILLIS * 3)
        compose.waitForIdle()

        assertThat(generator.calls).isEqualTo(1)
        assertThat(feedback.recorded).hasSize(1)
    }

    @Test
    fun `closing the panel during a call cancels it`() {
        var open by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { if (open) panel.Content(request = PanelRequest(whatsAppChat), onClose = {}) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertThat(generator.calls).isEqualTo(1)

        open = false
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(FakeGenerator.GENERATE_MILLIS * 3)
        compose.waitForIdle()

        assertThat(generator.calls).isEqualTo(1)
        assertThat(feedback.recorded).isEmpty()
    }

    /** The host passes `PanelSheetState::requestClose` (and with it the close fallback) as onClose. */
    @Test
    fun `insert, Close and Open settings each close through onClose once`() {
        var closes = 0
        var settingsOpened = 0
        val noKey =
            ComposeReplyPanel(
                ReplyPanelDependencies(
                    FakeAssembler(),
                    FakeGenerator(SuggestionResult.Failure.NoApiKey),
                    FakeInserter(),
                    feedback,
                    FakeNoteStore(),
                    FakeHistory(),
                ),
                openSettings = { settingsOpened++ },
            )
        var which by mutableStateOf(0)
        compose.setContent {
            key(which) {
                (if (which == 2) noKey else panel).Content(PanelRequest(whatsAppChat), onClose = { closes++ })
            }
        }
        compose.mainClock.advanceTimeBy(FakeGenerator.GENERATE_MILLIS * 2)
        compose.waitForIdle()

        compose.onNodeWithText("Haan pakka, 6 baje milte hain", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertThat(closes).isEqualTo(1)

        which = 1
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        assertThat(closes).isEqualTo(2)

        which = 2
        compose.mainClock.advanceTimeBy(FakeGenerator.GENERATE_MILLIS * 2)
        compose.waitForIdle()
        compose.onNodeWithText("Open settings").performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(settingsOpened).isEqualTo(1)
        assertThat(closes).isEqualTo(3)
    }
}
