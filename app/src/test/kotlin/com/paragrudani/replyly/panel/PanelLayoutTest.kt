package com.paragrudani.replyly.panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds

/** Gestures and TalkBack on the stateless layout. */
@RunWith(RobolectricTestRunner::class)
class PanelLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val inserted = mutableListOf<Int>()
    private val copied = mutableListOf<Int>()
    private val chips = mutableListOf<QuickInstruction>()

    private val actions =
        PanelActions(
            onInsert = { inserted += it },
            onCopy = { copied += it },
            onToggleChip = { chips += it },
        )

    private val ready =
        PanelUiState(
            suggestions = SuggestionsUi.Ready(threeSuggestions, "m", 900.milliseconds, 1),
            generation = 1,
        )

    private fun show(ui: PanelUiState) {
        compose.setContent {
            ReplylyTheme(darkTheme = false, dynamicColor = false, reducedMotion = true) {
                PanelLayout(ui, "Riya", ComposeReplyPanel.DEFAULT_MAX_HEIGHT, actions)
            }
        }
    }

    @Test
    fun `tap inserts, long-press copies`() {
        show(ready)

        compose.onNodeWithText("Kitne baje?", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Kal nahi ho payega yaar", useUnmergedTree = true).performTouchInput { longClick() }

        assertThat(inserted).containsExactly(2)
        assertThat(copied).containsExactly(1)
    }

    @Test
    fun `each card is one TalkBack stop with intent and text, and Insert and Copy actions`() {
        show(ready)

        val card = compose.onNodeWithText("Haan pakka, 6 baje milte hain").fetchSemanticsNode()
        val text = card.config[SemanticsProperties.Text].map { it.text }
        assertThat(text).containsExactly("Agree", "Haan pakka, 6 baje milte hain").inOrder()
        val actionsOnCard = card.config[SemanticsActions.CustomActions].map { it.label }
        assertThat(actionsOnCard).containsExactly("Insert", "Copy").inOrder()
        assertThat(card.config.getOrNull(SemanticsActions.OnClick)?.label).isEqualTo("Insert")
        assertThat(card.config.getOrNull(SemanticsActions.OnLongClick)?.label).isEqualTo("Copy")

        val copyAction = card.config[SemanticsActions.CustomActions].first { it.label == "Copy" }
        compose.runOnIdle { copyAction.action() }
        assertThat(copied).containsExactly(0)
    }

    @Test
    fun `a quick-chip tap reports that chip`() {
        show(ready)

        compose.onNodeWithText("More formal").performClick()

        assertThat(chips).containsExactly(QuickInstruction.MoreFormal)
    }

    @Test
    fun `chips and Regenerate are disabled while loading`() {
        show(PanelUiState(generation = 2))

        compose.onNodeWithText("Shorter").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Regenerate replies").assertIsNotEnabled()
    }

    @Test
    fun `chips and Regenerate are disabled while an insert runs and after it`() {
        var ui by mutableStateOf(ready.copy(inserting = true, insertingIndex = 0))
        compose.setContent {
            ReplylyTheme(darkTheme = false, dynamicColor = false, reducedMotion = true) {
                PanelLayout(ui, "Riya", ComposeReplyPanel.DEFAULT_MAX_HEIGHT, actions)
            }
        }
        compose.onNodeWithText("Shorter").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Regenerate replies").assertIsNotEnabled()

        ui = ready.copy(finished = true, insertingIndex = 0)
        compose.onNodeWithText("Shorter").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Regenerate replies").assertIsNotEnabled()

        ui = ready
        compose.onNodeWithText("Shorter").assertIsEnabled()
    }

    @Test
    fun `cards are disabled once an insert is running or done`() {
        show(ready.copy(finished = true, insertingIndex = 0))

        compose.onNodeWithText("Kitne baje?", useUnmergedTree = true).performScrollTo().performClick()

        assertThat(inserted).isEmpty()
    }

    @Test
    fun `icon buttons, chips and cards have 48 dp touch targets`() {
        show(ready)

        val nodes =
            listOf("Close", "Contact note", "Regenerate replies").map { compose.onNodeWithContentDescription(it) } +
                listOf("Add emoji", "Shorter").map { compose.onNodeWithText(it) } +
                compose.onNodeWithText("Haan pakka, 6 baje milte hain")
        nodes.forEach { node ->
            val bounds = node.fetchSemanticsNode().touchBoundsInRoot
            with(compose.density) {
                assertThat(bounds.width.toDp().value).isAtLeast(47.5f)
                assertThat(bounds.height.toDp().value).isAtLeast(47.5f)
            }
        }
    }

    @Test
    fun `the character counter shows only near the field's limit, which chips make smaller`() {
        var ui by mutableStateOf(ready.copy(instruction = "a".repeat(100)))
        compose.setContent {
            ReplylyTheme(darkTheme = false, dynamicColor = false, reducedMotion = true) {
                PanelLayout(ui, "Riya", ComposeReplyPanel.DEFAULT_MAX_HEIGHT, actions)
            }
        }
        compose.onNodeWithText("100/$MAX_INSTRUCTION_CHARS").assertDoesNotExist()

        ui = ready.copy(instruction = "a".repeat(MAX_INSTRUCTION_CHARS - INSTRUCTION_COUNTER_MARGIN))
        compose.onNodeWithText("270/$MAX_INSTRUCTION_CHARS").assertExists()

        val chips = QuickInstruction.entries.toSet()
        val room = instructionRoom(chips)
        ui = ready.copy(chips = chips, instruction = "a".repeat(room))
        compose.onNodeWithText("$room/$room").assertExists()
        compose.onNodeWithContentDescription("$room of $room characters").assertExists()
    }

    @Test
    fun `the Copied confirmation is shown only after a copy`() {
        show(ready.copy(copied = true))
        compose.onNodeWithContentDescription("Copied").assertExists()
    }
}
