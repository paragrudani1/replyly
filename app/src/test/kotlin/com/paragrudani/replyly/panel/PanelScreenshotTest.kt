package com.paragrudani.replyly.panel

import androidx.compose.runtime.Composable
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.testing.ScreenshotRule
import com.paragrudani.replyly.testing.ScreenshotTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.milliseconds

/** Every panel state in light, dark and font 130 %. Shots use reduced motion: static skeletons, end states. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotTest.DEVICE)
class PanelScreenshotTest {
    @get:Rule val shots = ScreenshotRule()

    @Test
    fun loading() = shot("panel/loading", PanelUiState(generation = 1))

    @Test
    fun cards() = shot("panel/cards", ready)

    @Test
    fun regenerating() =
        shot(
            "panel/regenerating",
            PanelUiState(generation = 2, chips = setOf(QuickInstruction.Shorter), instruction = "say I'll call at 6"),
        )

    @Test
    fun instructionChips() =
        shot(
            "panel/chips",
            ready.copy(
                generation = 3,
                instruction = "say I'll call at 6",
                chips = setOf(QuickInstruction.Shorter, QuickInstruction.InEnglish),
            ),
        )

    @Test
    fun instructionNearLimit() {
        val chips = setOf(QuickInstruction.Shorter, QuickInstruction.InEnglish)
        val room = instructionRoom(chips)
        shot(
            "panel/instruction_limit",
            ready.copy(
                generation = 2,
                chips = chips,
                instruction = "Say I'll call at 6 after the meeting, ".repeat(10).take(room),
            ),
        )
    }

    @Test
    fun nothingToReply() =
        shot("panel/nothing_to_reply", PanelUiState(suggestions = SuggestionsUi.NothingToReply, generation = 1))

    @Test
    fun copied() = shot("panel/copied", ready.copy(copied = true))

    @Test
    fun inserting() = shot("panel/inserting", ready.copy(inserting = true, insertingIndex = 0))

    @Test
    fun noteEditor() =
        shot(
            "panel/note",
            ready.copy(note = NoteEditorUi("College friend from Pune. We talk in Hinglish, very casual.")),
        )

    @Test
    fun deleteConfirmation() = shot("panel/delete_confirmation", ready.copy(confirmingDelete = true))

    @Test
    fun errorKey() =
        error(
            "panel/error_key",
            PanelError("No API key yet", "Add your API key in Settings.", ErrorAction.OpenSettings, ErrorKind.Key),
        )

    @Test
    fun errorSettings() =
        error(
            "panel/error_settings",
            PanelError(
                "The API returned an error (404)",
                "Model or endpoint not found. Check Settings.",
                ErrorAction.OpenSettings,
                ErrorKind.Settings,
            ),
        )

    @Test
    fun errorLimit() =
        error(
            "panel/error_limit",
            PanelError(
                "Daily limit reached",
                "You have used all 50 requests for today. It resets tomorrow, or raise the limit in Settings.",
                ErrorAction.OpenSettings,
                ErrorKind.Limit,
            ),
        )

    @Test
    fun errorConnection() =
        error(
            "panel/error_connection",
            PanelError("Could not reach the API", "No internet connection.", ErrorAction.Retry, ErrorKind.Connection),
        )

    @Test
    fun errorStorage() =
        error(
            "panel/error_storage",
            PanelError(
                "Secure storage is locked",
                "Replyly's encrypted storage is locked right now. Try again in a moment.",
                ErrorAction.Retry,
                ErrorKind.Storage,
            ),
        )

    @Test
    fun errorReply() =
        error(
            "panel/error_reply",
            PanelError(
                "Could not read the model's reply",
                "It was not in the expected format. Try again.",
                ErrorAction.Retry,
                ErrorKind.Reply,
            ),
        )

    private fun error(
        name: String,
        error: PanelError,
    ) = shot(name, PanelUiState(suggestions = SuggestionsUi.Error(error), generation = 1))

    private fun shot(
        name: String,
        ui: PanelUiState,
    ) = shots.component(name) { Sheet(ui) }

    @Composable
    private fun Sheet(ui: PanelUiState) {
        PanelSheetSurface { PanelLayout(ui, "Riya", ComposeReplyPanel.DEFAULT_MAX_HEIGHT, PanelActions()) }
    }

    private val ready =
        PanelUiState(
            suggestions =
                SuggestionsUi.Ready(
                    listOf(
                        Suggestion("agree", "Haan pakka, kal 6 baje milte hain. Main seedha office se aa jaunga"),
                        Suggestion("decline", "Kal nahi ho payega yaar, weekend pe plan karte hain?"),
                        Suggestion("ask", "Kitne baje aur kahan?"),
                    ),
                    model = "anthropic/claude-haiku-5.5",
                    latency = 1_400.milliseconds,
                    feedbackEntryId = 1,
                ),
            generation = 1,
        )
}
