package com.paragrudani.replyly.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.paragrudani.replyly.R
import com.paragrudani.replyly.overlay.BubbleLook
import com.paragrudani.replyly.overlay.OverlayButtonView
import com.paragrudani.replyly.settings.ReplylySettings
import com.paragrudani.replyly.ui.theme.Spacing
import kotlin.math.roundToInt

/** The controls of Settings → Bubble (#55); every one saves at once (no draft, no Save bar). */
class BubbleActions(
    val onShowBubble: (Boolean) -> Unit,
    val onSize: (Float) -> Unit,
    val onOpacity: (Int) -> Unit,
    val onShrinkWhenIdle: (Boolean) -> Unit,
    val onResetPositions: () -> Unit,
)

/**
 * Settings → Bubble (#55): a preview of the floating button, Show bubble, Size (4 steps),
 * Opacity (4 steps), Shrink when idle, Reset button positions. Switches save on change (with the
 * toggle haptic); sliders save when the finger lifts (`onValueChangeFinished`), and the preview
 * follows the slider while it moves.
 */
@Composable
fun BubbleSection(
    look: BubbleLook,
    actions: BubbleActions,
) {
    val sizes = ReplylySettings.BUBBLE_SIZES
    val opacities = ReplylySettings.BUBBLE_OPACITIES
    var sizeIndex by remember(look.size) { mutableIntStateOf(sizes.indexOf(look.size).coerceAtLeast(0)) }
    var opacityIndex by remember(look.opacityPercent) {
        mutableIntStateOf(opacities.indexOf(look.opacityPercent).coerceAtLeast(0))
    }
    SettingsSection("Bubble") {
        BubblePreview(look.copy(size = sizes[sizeIndex], opacityPercent = opacities[opacityIndex]))
        SettingsSwitchRow(
            label = "Show bubble",
            supporting = "The Replyly button above the keyboard. Off hides it in every app.",
            checked = look.show,
            onCheckedChange = actions.onShowBubble,
            haptic = true,
        )
        SettingsDivider()
        StepSliderRow(
            label = "Size",
            index = sizeIndex,
            count = sizes.size,
            valueText = { "${formatSize(sizes[it])}x" },
            spoken = { "${formatSize(sizes[it])} times" },
            onChange = { sizeIndex = it },
            onFinished = { actions.onSize(sizes[it]) },
            tag = BUBBLE_SIZE_SLIDER_TAG,
        )
        StepSliderRow(
            label = "Opacity",
            index = opacityIndex,
            count = opacities.size,
            valueText = { "${opacities[it]} %" },
            spoken = { "${opacities[it]} percent" },
            onChange = { opacityIndex = it },
            onFinished = { actions.onOpacity(opacities[it]) },
            tag = BUBBLE_OPACITY_SLIDER_TAG,
        )
        SettingsDivider()
        SettingsSwitchRow(
            label = "Shrink when idle",
            supporting = "After 5 seconds untouched the button becomes a small dot. One tap still opens Replyly.",
            checked = look.shrinkWhenIdle,
            onCheckedChange = actions.onShrinkWhenIdle,
            haptic = true,
        )
        SettingsDivider()
        SettingsNavRow(
            title = "Reset button positions",
            supporting = "Put the button back to its default spot in every app",
            onClick = actions.onResetPositions,
            onClickLabel = "Reset",
        )
    }
}

/** "0.85", "1", "1.15", "1.3": the step without trailing zeros. */
private fun formatSize(size: Float): String =
    when (size) {
        1.0f -> "1"
        else -> "%.2f".format(java.util.Locale.US, size).trimEnd('0')
    }

/** A label with its value on the right and a stepped slider below; TalkBack reads "Size, 1.15 times". */
@Composable
private fun StepSliderRow(
    label: String,
    index: Int,
    count: Int,
    valueText: (Int) -> String,
    spoken: (Int) -> String,
    onChange: (Int) -> Unit,
    onFinished: (Int) -> Unit,
    tag: String,
) {
    // The newest value, also when the slider finishes before a recomposition (a tap on the track).
    val latest = remember(index) { mutableIntStateOf(index) }
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.L, vertical = Spacing.S)) {
        // The slider carries the label and value for TalkBack, so this row is not a second stop.
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                valueText(index),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = index.toFloat(),
            onValueChange = {
                latest.intValue = it.roundToInt().coerceIn(0, count - 1)
                onChange(latest.intValue)
            },
            onValueChangeFinished = { onFinished(latest.intValue) },
            valueRange = 0f..(count - 1).toFloat(),
            // Positions between the two ends: 4 steps in all.
            steps = count - 2,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(tag)
                    .semantics {
                        contentDescription = label
                        stateDescription = spoken(index)
                    },
        )
    }
}

/**
 * The floating button as it will look (size and opacity), on a chat-like surface. Settings is
 * the one place the real button never shows. Decorative: the sliders read the values.
 */
@Composable
private fun BubblePreview(look: BubbleLook) {
    val largest = BubbleLook(size = ReplylySettings.BUBBLE_SIZES.last()).windowDp.dp
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.L, vertical = Spacing.S)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium)
            .height(largest + Spacing.L * 2)
            .clearAndSetSemantics {}
            .testTag(BUBBLE_PREVIEW_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(look.circleDp.dp)
                // One layer, like the real button's alpha: the mark does not show the fill through.
                // No shadow here: on a Settings surface, tone separates (DESIGN.md).
                .graphicsLayer { alpha = look.alpha(busy = false) }
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.overlay_button_icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size((OverlayButtonView.ICON_DP * look.size).dp),
            )
        }
    }
}

/** "Reset button positions?" with Cancel and Reset (M3 basic dialog around [ResetPositionsDialogContent]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResetPositionsDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) { ResetPositionsDialogContent(onConfirm, onDismiss) }
}

/** The dialog's card, also shot on its own (a dialog window is outside the screenshot root). */
@Composable
fun ResetPositionsDialogContent(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = "Reset button positions?"
    Surface(
        shape = AlertDialogDefaults.shape,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
        modifier = Modifier.semantics { paneTitle = title },
    ) {
        Column(Modifier.padding(Spacing.XL)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = AlertDialogDefaults.titleContentColor)
            Spacer(Modifier.height(Spacing.L))
            Text(
                "The bubble goes back to its default spot in every app.",
                style = MaterialTheme.typography.bodyMedium,
                color = AlertDialogDefaults.textContentColor,
            )
            Spacer(Modifier.height(Spacing.XL))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.S, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = onConfirm) { Text("Reset") }
            }
        }
    }
}

internal const val BUBBLE_SIZE_SLIDER_TAG = "bubble_size_slider"
internal const val BUBBLE_OPACITY_SLIDER_TAG = "bubble_opacity_slider"
internal const val BUBBLE_PREVIEW_TAG = "bubble_preview"
