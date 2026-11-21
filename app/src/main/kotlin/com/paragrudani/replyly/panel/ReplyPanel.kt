package com.paragrudani.replyly.panel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.paragrudani.replyly.parser.ParsedChat

/** What the overlay (#1) hands the panel when the user taps the floating button. */
data class PanelRequest(
    /** The chat as parsed at the moment of the tap. Already checked by SensitiveScreenPolicy. */
    val chat: ParsedChat,
)

/**
 * The suggestions panel (ticket #5): 3 cards, instruction + Regenerate, contact note, loading
 * and error states. Ticket #1's overlay window hosts [Content] in a ComposeView.
 *
 * Opening the panel is the user's button tap, so the panel may call the LLM once on open and
 * again on Regenerate, never otherwise. It gets its dependencies (generator, assembler, cost
 * guard, feedback log, contact notes, inserter) from AppGraph.
 */
interface ReplyPanel {
    @Composable
    fun Content(
        request: PanelRequest,
        onClose: () -> Unit,
    )
}

/** Stub until ticket #5. */
object PlaceholderReplyPanel : ReplyPanel {
    @Composable
    override fun Content(
        request: PanelRequest,
        onClose: () -> Unit,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Replyly panel (ticket #5)")
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}
