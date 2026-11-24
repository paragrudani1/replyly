package com.paragrudani.replyly.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.paragrudani.replyly.snooze.SnoozeChoice
import com.paragrudani.replyly.ui.theme.Elevation
import com.paragrudani.replyly.ui.theme.MinTouchTarget
import com.paragrudani.replyly.ui.theme.MotionDurations
import com.paragrudani.replyly.ui.theme.MotionEasing
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import com.paragrudani.replyly.ui.theme.Spacing

/** The snooze card's rows, in order, with their labels (#56). */
val SnoozeChoice.label: String
    get() =
        when (this) {
            SnoozeChoice.TEN_MINUTES -> "10 minutes"
            SnoozeChoice.ONE_HOUR -> "1 hour"
            SnoozeChoice.UNTIL_MORNING -> "Until tomorrow (6 AM)"
            SnoozeChoice.UNTIL_TURNED_ON -> "Until I turn it back on"
        }

/**
 * The snooze card (#56), Replyly's own UI over the chat: a small card [bottomGap] above the
 * screen bottom (the keyboard's top) with the four choices as 48 dp rows and a Cancel button,
 * over a transparent full-screen layer where a tap anywhere outside the card cancels. Choosing a
 * row is a tap on Replyly's window, never an action on the chat app; nothing here calls the LLM.
 * The outside layer has no TalkBack node: TalkBack users cancel with the Cancel button.
 */
@Composable
fun SnoozePicker(
    onChoose: (SnoozeChoice) -> Unit,
    onCancel: () -> Unit,
    bottomGap: Dp,
    modifier: Modifier = Modifier,
) {
    val motion = ReplylyTheme.motion
    val alpha = remember { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) { alpha.animateTo(1f, motion.tween(MotionDurations.MEDIUM, MotionEasing.Enter)) }
    Box(modifier.fillMaxSize()) {
        // Below the card: a tap anywhere else cancels. No TalkBack node (Cancel is the accessible way).
        Box(
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics { testTag = SNOOZE_OUTSIDE_TAG }
                .pointerInput(onCancel) { detectTapGestures { onCancel() } },
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            // Floats over another app: the one place a shadow is right (DESIGN.md).
            shadowElevation = Elevation.Sheet,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = Spacing.L, end = Spacing.L, top = Spacing.XL, bottom = bottomGap + Spacing.L)
                    .widthIn(max = CARD_MAX_WIDTH)
                    .fillMaxWidth()
                    .graphicsLayer { this.alpha = alpha.value }
                    .testTag(SNOOZE_CARD_TAG)
                    .semantics { paneTitle = TITLE },
        ) {
            // Scrolls when little room is left above the keyboard (landscape).
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = Spacing.S)) {
                Text(
                    TITLE,
                    style = MaterialTheme.typography.titleMedium,
                    modifier =
                        Modifier
                            .padding(horizontal = Spacing.XL, vertical = Spacing.S)
                            .semantics { heading() },
                )
                SnoozeChoice.entries.forEach { choice ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = MinTouchTarget)
                                .clickable(onClickLabel = "Snooze", role = Role.Button) { onChoose(choice) }
                                .padding(horizontal = Spacing.XL, vertical = Spacing.S),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(choice.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.M),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    }
}

internal const val SNOOZE_OUTSIDE_TAG = "snooze_outside"
internal const val SNOOZE_CARD_TAG = "snooze_card"
private const val TITLE = "Snooze the bubble"
private val CARD_MAX_WIDTH = 360.dp

/**
 * The snooze card's own accessibility overlay window (#56): full screen, so a tap outside the
 * card lands on it and cancels, and not focusable, so the chat keeps its input focus and
 * keyboard. [onChoose] stores the snooze and returns the toast text; the window closes on a
 * choice, on cancel, and on [close] (the controller closes it whenever the button would hide).
 * Main thread only.
 */
class SnoozePickerWindow(
    private val context: Context,
    private val onChoose: (SnoozeChoice) -> String,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owners: OverlayViewOwners? = null

    val isOpen: Boolean get() = view != null

    /** Opens the card above [keyboardTop] (a screen y in px). */
    fun open(keyboardTop: Int) {
        close()
        val metrics = context.resources.displayMetrics
        val screenHeight = windowManager.currentWindowMetrics.bounds.height()
        val gapDp = ((screenHeight - keyboardTop).coerceAtLeast(0) / metrics.density).dp
        val owners = OverlayViewOwners()
        val compose =
            ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    ReplylyTheme { SnoozePicker(onChoose = ::choose, onCancel = ::close, bottomGap = gapDp) }
                }
            }
        owners.attach(compose)
        windowManager.addView(compose, params())
        view = compose
        this.owners = owners
    }

    /** A row was tapped: store, close, then the toast (the button hides on the store change). */
    @VisibleForTesting
    internal fun choose(choice: SnoozeChoice) {
        if (!isOpen) return
        val toast = onChoose(choice)
        close()
        Toast.makeText(context, toast, Toast.LENGTH_LONG).show()
    }

    fun close() {
        val compose = view ?: return
        view = null
        try {
            windowManager.removeViewImmediate(compose)
        } catch (e: IllegalArgumentException) {
            // Already removed (the service is going away).
        }
        compose.disposeComposition()
        owners?.destroy()
        owners = null
    }

    /** The window's root while open (tests). */
    @VisibleForTesting
    internal val root: ComposeView? get() = view

    @SuppressLint("RtlHardcoded")
    private fun params() =
        WindowManager
            .LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
}
