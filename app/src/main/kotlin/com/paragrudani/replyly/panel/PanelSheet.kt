package com.paragrudani.replyly.panel

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.paragrudani.replyly.ui.theme.Elevation
import com.paragrudani.replyly.ui.theme.MotionDurations
import com.paragrudani.replyly.ui.theme.MotionEasing
import com.paragrudani.replyly.ui.theme.ReplylyMotion
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import com.paragrudani.replyly.ui.theme.SheetShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Asks an open [PanelSheet] to close. The sheet slides down, then calls its `onClosed`.
 * Main thread only; asking twice closes once.
 *
 * Every close goes through [requestClose]: the panel's own (insert, Close, Open settings), the
 * host's Back key, and the sheet's backdrop tap and swipe down. So [onCloseRequested] runs once
 * for any of them: the host uses it for a fallback that removes the window if the slide-down
 * never finishes (no frame runs).
 */
@Stable
class PanelSheetState(
    private val onCloseRequested: () -> Unit = {},
) {
    var closeRequested by mutableStateOf(false)
        private set

    fun requestClose() {
        if (closeRequested) return
        closeRequested = true
        onCloseRequested()
    }
}

/**
 * The panel's window content: a dimmed backdrop and a bottom sheet above the keyboard, padded by
 * `WindowInsets.safeDrawing` (keyboard, navigation bar, status bar, cutout), so its top bar stays
 * below the status bar when the keyboard leaves little room.
 *
 * - **Open:** the sheet slides up from below the screen with the theme spring; the backdrop fades in
 *   with it.
 * - **Close** ([PanelSheetState.requestClose]: a card insert, Close, Back, a tap on the backdrop, a
 *   swipe down): the sheet slides down and the backdrop fades out, then [onClosed] runs once.
 * - **Swipe down** on the sheet (also after the body is scrolled to its top) drags it; let go past
 *   a third of the way, or with a downward fling, and it closes, else it springs back.
 * - With reduced motion every step snaps: it appears and disappears at once.
 *
 * The backdrop is not a TalkBack stop (the sheet has its own Close button).
 */
@Composable
fun PanelSheet(
    state: PanelSheetState,
    onClosed: () -> Unit,
    content: @Composable () -> Unit,
) {
    val motion = ReplylyTheme.motion
    val currentOnClosed by rememberUpdatedState(onClosed)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // How far the sheet must move down to be fully off screen (its top to the window's bottom).
    var hideDistance by remember { mutableFloatStateOf(0f) }
    var placed by remember { mutableStateOf(false) }
    // True once the sheet sits at its start position below the screen: before that it is not drawn on screen.
    var entered by remember { mutableStateOf(false) }
    val offset = remember { Animatable(0f) }
    val dismissVelocity = with(density) { DISMISS_VELOCITY.toPx() }

    LaunchedEffect(placed) {
        if (placed && !state.closeRequested) {
            offset.snapTo(hideDistance)
            entered = true
            offset.animateTo(0f, motion.spring())
        }
    }
    LaunchedEffect(state.closeRequested) {
        if (state.closeRequested) {
            offset.animateTo(maxOf(hideDistance, offset.value), motion.tween(MotionDurations.LONG, MotionEasing.Exit))
            currentOnClosed()
        }
    }

    val drag =
        remember(offset, motion, dismissVelocity) {
            SheetDrag(offset, scope, motion, { hideDistance }, dismissVelocity, state)
        }
    val visibleFraction =
        when {
            !entered -> 0f
            hideDistance <= 0f -> 1f
            else -> (1f - offset.value / hideDistance).coerceIn(0f, 1f)
        }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = visibleFraction }
                .background(ReplylyTheme.colors.backdrop)
                .pointerInput(state) { detectTapGestures { state.requestClose() } },
        )
        PanelSheetSurface(
            Modifier
                .align(Alignment.BottomCenter)
                // Keyboard and navigation bar below, status bar (and cutout) above: with Gboard up
                // the window is short, and the sheet shrinks rather than slide under the status bar.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .onGloballyPositioned { coordinates ->
                    // Measured before the translation below, so this is the resting position.
                    val root = coordinates.findRootCoordinates()
                    hideDistance = root.size.height - coordinates.positionInRoot().y
                    placed = true
                }.graphicsLayer {
                    translationY = if (entered) offset.value else OFF_SCREEN
                }.nestedScroll(drag.connection)
                .draggable(
                    rememberDraggableState(drag::dragBy),
                    Orientation.Vertical,
                    onDragStopped = { velocity -> drag.settle(velocity) },
                ),
        ) {
            content()
        }
    }
}

/** Before the entrance starts the sheet's size is unknown: keep it far below the screen. */
private const val OFF_SCREEN = 100_000f

/**
 * The sheet's surface (shape, tone, shadow) without motion. [PanelSheet] uses it; screenshot tests
 * use it to show the panel the way it looks on screen.
 */
@Composable
fun PanelSheetSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = SheetShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = Elevation.Flat,
        shadowElevation = Elevation.Sheet,
        content = content,
    )
}

/** Drag-to-dismiss for the sheet, from a direct drag or from the body's nested scroll. */
private class SheetDrag(
    private val offset: Animatable<Float, *>,
    private val scope: CoroutineScope,
    private val motion: ReplylyMotion,
    private val hideDistance: () -> Float,
    private val dismissVelocity: Float,
    private val state: PanelSheetState,
) {
    fun dragBy(delta: Float) {
        if (state.closeRequested) return
        val next = (offset.value + delta).coerceAtLeast(0f)
        // Undispatched: an uncontended snap finishes before this returns, so offset.value is current.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(next) }
    }

    fun settle(velocity: Float) {
        if (state.closeRequested) return
        val distance = hideDistance()
        val dismiss = velocity > dismissVelocity || (distance > 0f && offset.value > distance * DISMISS_FRACTION)
        if (dismiss) {
            state.requestClose()
        } else {
            scope.launch { offset.animateTo(0f, motion.spring()) }
        }
    }

    val connection =
        object : NestedScrollConnection {
            // Sheet pulled down and the user drags back up: move the sheet before the body scrolls.
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y >= 0f || offset.value <= 0f ||
                    source != NestedScrollSource.UserInput
                ) {
                    return Offset.Zero
                }
                val used = maxOf(available.y, -offset.value)
                dragBy(used)
                return Offset(0f, used)
            }

            // The body is at its top and the user keeps dragging down: pull the sheet.
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                dragBy(available.y)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset.value <= 0f) return Velocity.Zero
                settle(available.y)
                return available
            }
        }

    private companion object {
        const val DISMISS_FRACTION = 1f / 3f
    }
}

/** A downward fling faster than this closes the sheet. */
private val DISMISS_VELOCITY = 1_000.dp
