package com.paragrudani.replyly.panel

import androidx.compose.runtime.Composable
import com.paragrudani.replyly.llm.NothingToReplyReason
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.testing.ScreenshotRule
import com.paragrudani.replyly.testing.ScreenshotTest
import com.paragrudani.replyly.testing.ShotVariant
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
        shot("panel/nothing_to_reply", PanelUiState(suggestions = SuggestionsUi.NothingToReply(), generation = 1))

    /** Issue #38: their media came after my last message. */
    @Test
    fun nothingToReplyTheirMedia() =
        shot(
            "panel/nothing_to_reply_their_media",
            PanelUiState(
                suggestions = SuggestionsUi.NothingToReply(NothingToReplyReason.TheirMediaAfterMine),
                generation = 1,
            ),
            variants = ShotVariant.LIGHT_DARK,
        )

    /** Issue #38: no message of mine (an empty chat, or only their media). */
    @Test
    fun nothingToReplyNoMessageOfMine() =
        shot(
            "panel/nothing_to_reply_no_message_of_mine",
            PanelUiState(
                suggestions = SuggestionsUi.NothingToReply(NothingToReplyReason.NoMessageOfMine),
                generation = 1,
            ),
            variants = ShotVariant.LIGHT_DARK,
        )

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

    /** X (#30): counters on every card, the first one trimmed (error colour), X intents and chips. */
    @Test
    fun xCards() = shot("panel/x_cards", xReady, title = "Meera Iyer")

    /** X: one card could not be trimmed and was dropped; the notice says so. */
    @Test
    fun xDropped() =
        shot(
            "panel/x_dropped",
            xReady.copy(
                suggestions =
                    (xReady.suggestions as SuggestionsUi.Ready).let {
                        it.copy(suggestions = it.suggestions.drop(1), droppedOverLimit = 1)
                    },
            ),
            title = "Meera Iyer",
        )

    private fun error(
        name: String,
        error: PanelError,
    ) = shot(name, PanelUiState(suggestions = SuggestionsUi.Error(error), generation = 1))

    private fun shot(
        name: String,
        ui: PanelUiState,
        title: String = "Riya",
        variants: List<ShotVariant> = ShotVariant.ALL,
    ) = shots.component(name, variants) { Sheet(ui, title) }

    @Composable
    private fun Sheet(
        ui: PanelUiState,
        title: String,
    ) {
        PanelSheetSurface { PanelLayout(ui, title, ComposeReplyPanel.DEFAULT_MAX_HEIGHT, PanelActions()) }
    }

    private val xReady =
        PanelUiState(
            suggestions =
                SuggestionsUi.Ready(
                    listOf(
                        Suggestion(
                            "insight",
                            "Local-first is the easy half. The hard half is sync: pick a data model that can merge " +
                                "from day one, or every later fix turns into a migration.",
                            trimmed = true,
                        ),
                        Suggestion(
                            "question",
                            "How do you handle conflicts once sync lands? That seems like the hard part. 🤔",
                        ),
                        Suggestion(
                            "humour",
                            "Day one backend: when the side project gets a load balancer before it gets a user.",
                        ),
                    ),
                    model = "anthropic/claude-haiku-5.5",
                    latency = 1_600.milliseconds,
                    feedbackEntryId = 1,
                    lengthLimit = 280,
                ),
            generation = 1,
            showChatActions = false,
            isX = true,
        )

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
