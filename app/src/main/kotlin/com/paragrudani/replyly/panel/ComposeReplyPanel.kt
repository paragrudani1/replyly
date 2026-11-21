package com.paragrudani.replyly.panel

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.paragrudani.replyly.MainActivity
import com.paragrudani.replyly.R
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.ui.theme.HapticEvent
import com.paragrudani.replyly.ui.theme.MotionDurations
import com.paragrudani.replyly.ui.theme.MotionEasing
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import com.paragrudani.replyly.ui.theme.Spacing
import com.paragrudani.replyly.ui.theme.rememberReplylyHaptics
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * The suggestions panel (ticket #5), built to run inside ticket #1's accessibility overlay
 * window, where there is no Activity.
 *
 * ## How the host (#1) provides it
 *
 * `overlay/OverlayWindows.kt` hosts it; keep these properties if that changes:
 *
 * - A `ComposeView` added with `WindowManager.addView`, using the accessibility service as context,
 *   with `setViewTreeLifecycleOwner`, `setViewTreeSavedStateRegistryOwner` and
 *   `setViewTreeViewModelStoreOwner` set on its root before it is attached (`OverlayViewOwners`).
 *   The lifecycle is RESUMED while the panel is shown, DESTROYED (plus `disposeComposition()`) when
 *   it is removed. Compose throws without the lifecycle and saved-state owners; the panel itself
 *   never calls `viewModel()`.
 * - A full-screen, **focusable modal** window: `TYPE_ACCESSIBILITY_OVERLAY`, MATCH_PARENT both ways,
 *   without `FLAG_NOT_TOUCH_MODAL`, `FLAG_NOT_FOCUSABLE` or `FLAG_ALT_FOCUSABLE_IM`, so the
 *   instruction field and the note editor get the keyboard. Touches do not pass through to the chat
 *   app: a dimmed backdrop covers it, and tapping the backdrop closes the panel.
 * - `SOFT_INPUT_ADJUST_RESIZE`, and the panel's sheet is padded by the IME and navigation-bar insets
 *   (`WindowInsets.ime.union(WindowInsets.navigationBars)`), so it sits right above Gboard. Not
 *   ADJUST_PAN, which shifts the whole window up instead of moving only the sheet.
 * - The host wraps [Content] in [PanelSheet] (backdrop, sheet surface, slide-up spring, swipe down)
 *   and passes `PanelSheetState::requestClose` (plus a time-out fallback) as `onClose`. So every
 *   close the panel asks for (insert, Close, Open settings) slides the sheet down first; the window
 *   is removed when the sheet's `onClosed` runs.
 * - When the user types into the panel, the chat app's input field loses input focus. The
 *   inserter therefore writes into the input node remembered when the button was tapped, and the
 *   overlay does not hide the panel because the chat field lost focus.
 * - One composition per panel open. Opening the panel = composing [Content]; that composition
 *   makes the LLM call. Disposing and composing it again (for example on a re-layout) makes a
 *   new call, so the host keeps the same `ComposeView` and composition while the panel stays open.
 * - The panel has no back handling (an overlay has no `OnBackPressedDispatcherOwner`); the host's
 *   root view calls the same `onClose` on KEYCODE_BACK. The panel applies its own theme and caps its
 *   height at [maxHeight].
 *
 * ## Motion and gestures
 *
 * Loading shows 3 shimmering skeleton cards (static with reduced motion). The 3 cards fade and slide
 * in, staggered by `MotionDurations.STAGGER`. Regenerate (or a chip) cross-fades the cards out to
 * the skeleton and back in. Tap a card: highlight, insert, `HapticEvent.CardInsert` once the text
 * is in the field, then the sheet slides down. Long-press: copy (marked sensitive),
 * `HapticEvent.LongPressCopy`, a "Copied" pill. None of the motion starts or
 * delays an LLM call; [ReplyPanelState] decides calls.
 *
 * The panel uses no Dialog, Popup, `LocalActivity`, Activity ViewModelStore or activity result
 * launcher: those need an Activity window token. Its state lives in [ReplyPanelState], created
 * with an unkeyed `remember` (the [PanelRequest] passed when the panel opens is the one used; later
 * requests are ignored until the next open). The state's own job is cancelled when the
 * composition forgets it, so closing the panel cancels any call still in flight.
 */
class ComposeReplyPanel(
    private val deps: ReplyPanelDependencies,
    private val openSettings: (Context) -> Unit = ::openReplylySettings,
    private val maxHeight: Dp = DEFAULT_MAX_HEIGHT,
    private val clipboard: (Context) -> SuggestionClipboard = ::AndroidSuggestionClipboard,
) : ReplyPanel {
    @Composable
    override fun Content(
        request: PanelRequest,
        onClose: () -> Unit,
    ) {
        val currentOnClose by rememberUpdatedState(onClose)
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        // Unkeyed on purpose: one state (and one LLM call) per panel open. A new or equal
        // [request] while the panel stays open never starts another call.
        val state =
            remember {
                DisposingPanelState(
                    ReplyPanelState(
                        request,
                        deps,
                        scope,
                        onClose = { currentOnClose() },
                        clipboard = clipboard(context),
                    ),
                )
            }.state
        LaunchedEffect(state) { state.start() }
        val ui by state.state.collectAsState()
        // Haptics on success (docs/DESIGN.md "Haptics"): the text is in the chat field, or on the clipboard.
        val haptics = rememberReplylyHaptics()
        LaunchedEffect(ui.finished) { if (ui.finished) haptics.perform(HapticEvent.CardInsert) }
        LaunchedEffect(ui.copies) { if (ui.copies > 0) haptics.perform(HapticEvent.LongPressCopy) }
        ReplylyTheme {
            PanelLayout(
                ui = ui,
                chatTitle = request.chat.title,
                maxHeight = maxHeight,
                actions =
                    PanelActions(
                        onClose = { currentOnClose() },
                        onInstructionChange = state::onInstructionChange,
                        onRegenerate = state::regenerate,
                        onInsert = state::insert,
                        onCopy = state::copy,
                        onToggleChip = state::toggleChip,
                        onOpenSettings = {
                            openSettings(context)
                            currentOnClose()
                        },
                        onOpenNote = state::openNote,
                        onNoteChange = state::onNoteChange,
                        onSaveNote = state::saveNote,
                        onCancelNote = state::cancelNote,
                        onRequestDelete = state::requestDelete,
                        onConfirmDelete = state::confirmDelete,
                        onCancelDelete = state::cancelDelete,
                    ),
            )
        }
    }

    companion object {
        /**
         * Room for 3 short cards plus the instruction field and its chips, while about 250 dp of
         * the chat stays visible above the sheet on the S23 (780 dp tall). With the keyboard up the
         * window is shorter and caps the sheet itself; the cards scroll when they need more room.
         */
        val DEFAULT_MAX_HEIGHT: Dp = 480.dp
    }
}

/** Cancels the state's coroutines when the composition forgets it (panel closed). */
private class DisposingPanelState(
    val state: ReplyPanelState,
) : RememberObserver {
    override fun onRemembered() = Unit

    override fun onForgotten() = state.dispose()

    override fun onAbandoned() = state.dispose()
}

/** Opens Replyly on its settings screen from the overlay (no Activity here, so a new task). */
fun openReplylySettings(context: Context) {
    context.startActivity(MainActivity.settingsIntent(context))
}

internal class PanelActions(
    val onClose: () -> Unit = {},
    val onInstructionChange: (String) -> Unit = {},
    val onRegenerate: () -> Unit = {},
    val onInsert: (Int) -> Unit = {},
    val onCopy: (Int) -> Unit = {},
    val onToggleChip: (QuickInstruction) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onOpenNote: () -> Unit = {},
    val onNoteChange: (String) -> Unit = {},
    val onSaveNote: () -> Unit = {},
    val onCancelNote: () -> Unit = {},
    val onRequestDelete: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
)

/** The sheet's content. Stateless: screenshot tests render it with fixed [ui]. */
@Composable
internal fun PanelLayout(
    ui: PanelUiState,
    chatTitle: String,
    maxHeight: Dp,
    actions: PanelActions,
) {
    val motion = ReplylyTheme.motion
    Box {
        Column(
            Modifier
                .heightIn(max = maxHeight)
                .padding(start = Spacing.L, end = Spacing.L, bottom = Spacing.M),
        ) {
            Grabber()
            TopBar(ui, chatTitle, actions)
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                val note = ui.note
                if (note != null) {
                    NoteEditor(note, chatTitle, actions)
                } else {
                    SuggestionsBody(ui, actions)
                    ui.insertError?.let { ErrorLine(it) }
                    ui.notice?.let { Notice(it) }
                    if (ui.confirmingDelete) {
                        DeleteConfirmation(chatTitle, actions)
                    } else if (ui.showChatActions) {
                        DeleteHistoryButton(actions.onRequestDelete)
                    }
                }
            }
            if (ui.note == null) InstructionArea(ui, actions)
        }
        AnimatedVisibility(
            visible = ui.copied,
            enter =
                fadeIn(motion.tween(MotionDurations.MEDIUM, MotionEasing.Enter)) +
                    scaleIn(motion.tween(MotionDurations.MEDIUM, MotionEasing.Enter), initialScale = 0.9f),
            exit = fadeOut(motion.tween(MotionDurations.MEDIUM, MotionEasing.Exit)),
            // Centred on the top bar, over the gap between the title and the icons.
            modifier = Modifier.align(Alignment.TopCenter).padding(top = Spacing.L + Spacing.XS),
        ) {
            CopiedPill()
        }
    }
}

/** The drag handle. Decorative: the whole sheet takes the swipe, Close is the TalkBack way out. */
@Composable
private fun Grabber() {
    Box(Modifier.fillMaxWidth().padding(top = Spacing.S), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
    }
}

@Composable
private fun TopBar(
    ui: PanelUiState,
    chatTitle: String,
    actions: PanelActions,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(R.drawable.ic_sparkle),
            contentDescription = null,
            tint = ReplylyTheme.colors.accent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Spacing.S))
        Text(
            chatTitle,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (ui.showChatActions && ui.note == null) {
            IconButton(onClick = actions.onOpenNote) {
                Icon(painterResource(R.drawable.ic_panel_edit), contentDescription = "Contact note")
            }
        }
        IconButton(onClick = actions.onClose) {
            Icon(painterResource(R.drawable.ic_panel_close), contentDescription = "Close")
        }
    }
}

/** What the body shows; a new value cross-fades. Each generation is its own value, so regenerate animates. */
private sealed interface BodyKey {
    data class Loading(
        val regenerating: Boolean,
    ) : BodyKey

    data class Ready(
        val generation: Int,
    ) : BodyKey

    data class Error(
        val generation: Int,
    ) : BodyKey
}

@Composable
private fun SuggestionsBody(
    ui: PanelUiState,
    actions: PanelActions,
) {
    val motion = ReplylyTheme.motion
    AnimatedContent(
        targetState = ui,
        contentKey = { it.bodyKey() },
        transitionSpec = {
            val enter =
                if (targetState.suggestions is SuggestionsUi.Ready) {
                    // The cards bring their own staggered entrance.
                    EnterTransition.None
                } else {
                    fadeIn(motion.tween(MotionDurations.LONG, MotionEasing.Enter))
                }
            val exit =
                fadeOut(motion.tween(MotionDurations.MEDIUM, MotionEasing.Exit)) +
                    slideOutVertically(motion.tween(MotionDurations.MEDIUM, MotionEasing.Exit)) { it / 8 }
            (enter togetherWith exit).using(SizeTransform(clip = false) { _, _ -> motion.spring() })
        },
        label = "suggestions body",
    ) { target ->
        // The content still on screen uses the live state (highlight, enabled); an outgoing one
        // keeps the state it was created with.
        val shown = if (target.bodyKey() == ui.bodyKey()) ui else target
        when (val suggestions = shown.suggestions) {
            SuggestionsUi.Loading -> {
                SkeletonCards(regenerating = shown.generation > 1)
            }

            is SuggestionsUi.Ready -> {
                ReadyCards(suggestions, shown, actions)
            }

            is SuggestionsUi.Error -> {
                ErrorState(suggestions.error, actions.onOpenSettings, actions.onRegenerate)
            }
        }
    }
}

private fun PanelUiState.bodyKey(): BodyKey =
    when (suggestions) {
        SuggestionsUi.Loading -> BodyKey.Loading(regenerating = generation > 1)
        is SuggestionsUi.Ready -> BodyKey.Ready(generation)
        is SuggestionsUi.Error -> BodyKey.Error(generation)
    }

@Composable
private fun ReadyCards(
    ready: SuggestionsUi.Ready,
    ui: PanelUiState,
    actions: PanelActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        ready.suggestions.forEachIndexed { index, suggestion ->
            StaggeredItem(index, key = ui.generation) {
                SuggestionCard(
                    suggestion = suggestion,
                    highlighted = ui.insertingIndex == index,
                    enabled = !ui.inserting && !ui.finished,
                    onInsert = { actions.onInsert(index) },
                    onCopy = { actions.onCopy(index) },
                )
            }
        }
        Text(
            "Tap to insert, hold to copy · ${formatSeconds(ready.latency.inWholeMilliseconds)} · ${ready.model}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatSeconds(millis: Long): String = String.format(Locale.ROOT, "%.1f s", millis / 1000.0)

private val previewReady =
    PanelUiState(
        suggestions =
            SuggestionsUi.Ready(
                listOf(
                    Suggestion(
                        "agree",
                        "Haan pakka, kal 6 baje milte hain. Main seedha office se aa jaunga, thoda late ho sakta hai",
                    ),
                    Suggestion("decline", "Kal nahi ho payega yaar, weekend pe plan karte hain?"),
                    Suggestion("ask", "Kitne baje aur kahan?"),
                ),
                model = "anthropic/claude-haiku-5.5",
                latency = 1_400.milliseconds,
                feedbackEntryId = 1,
            ),
        generation = 1,
    )

@Preview(widthDp = 360)
@Composable
private fun PanelReadyPreview() {
    ReplylyTheme(darkTheme = false, dynamicColor = false, reducedMotion = true) {
        PanelSheetSurface {
            PanelLayout(previewReady, "Riya", ComposeReplyPanel.DEFAULT_MAX_HEIGHT, PanelActions())
        }
    }
}

@Preview(widthDp = 360)
@Composable
private fun PanelErrorPreview() {
    val ui =
        PanelUiState(
            suggestions =
                SuggestionsUi.Error(
                    PanelError(
                        "Daily limit reached",
                        "You have used all 50 requests for today.",
                        ErrorAction.OpenSettings,
                        ErrorKind.Limit,
                    ),
                ),
        )
    ReplylyTheme(darkTheme = false, dynamicColor = false, reducedMotion = true) {
        PanelSheetSurface { PanelLayout(ui, "Riya", ComposeReplyPanel.DEFAULT_MAX_HEIGHT, PanelActions()) }
    }
}
