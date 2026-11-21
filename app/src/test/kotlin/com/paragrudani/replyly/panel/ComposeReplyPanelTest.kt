package com.paragrudani.replyly.panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
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
}
