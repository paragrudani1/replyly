package com.paragrudani.replyly.panel

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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotTest.DEVICE)
class PanelScreenshotTest {
    @get:Rule val shots = ScreenshotRule()

    @Test
    fun loading() =
        shots.component("panel/loading") {
            PanelLayout(PanelUiState(), "Riya", ComposeReplyPanel.DEFAULT_MAX_HEIGHT, PanelActions())
        }

    @Test
    fun cards() =
        shots.component("panel/cards") {
            PanelLayout(ready, "Riya", ComposeReplyPanel.DEFAULT_MAX_HEIGHT, PanelActions())
        }

    @Test
    fun error() =
        shots.component("panel/error") {
            val ui =
                PanelUiState(
                    suggestions =
                        SuggestionsUi.Error(
                            PanelError("Could not reach the API", "No internet connection.", ErrorAction.Retry),
                        ),
                )
            PanelLayout(ui, "Riya", ComposeReplyPanel.DEFAULT_MAX_HEIGHT, PanelActions())
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
        )
}
