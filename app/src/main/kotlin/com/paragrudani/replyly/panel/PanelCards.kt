package com.paragrudani.replyly.panel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.paragrudani.replyly.R
import com.paragrudani.replyly.llm.NothingToReplyReason
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.llm.XLength
import com.paragrudani.replyly.ui.theme.MotionDurations
import com.paragrudani.replyly.ui.theme.MotionEasing
import com.paragrudani.replyly.ui.theme.Radii
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import com.paragrudani.replyly.ui.theme.Spacing

/** One full shimmer sweep across a skeleton card. */
private const val SHIMMER_MILLIS = 1_200

/**
 * Three placeholder cards while a call runs, with a shimmer sweeping across them. With reduced
 * motion the shimmer never starts: the cards are static. [regenerating] only changes the status
 * line TalkBack announces.
 */
@Composable
internal fun SkeletonCards(regenerating: Boolean) {
    val brush = shimmerBrush()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        SKELETON_LINES.forEach { lines -> SkeletonCard(brush, lines) }
        Text(
            if (regenerating) "Writing 3 new replies…" else "Writing 3 replies…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** Line widths of the 3 skeleton cards: different, so they read as text, not as bars. */
private val SKELETON_LINES = listOf(listOf(0.92f, 0.55f), listOf(0.8f, 0.35f), listOf(0.5f))

@Composable
private fun SkeletonCard(
    brush: Brush,
    lines: List<Float>,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(Spacing.M)
            .clearAndSetSemantics {},
        verticalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        SkeletonBlock(brush, Modifier.width(52.dp).height(18.dp))
        lines.forEach { fraction -> SkeletonBlock(brush, Modifier.fillMaxWidth(fraction).height(12.dp)) }
    }
}

@Composable
private fun SkeletonBlock(
    brush: Brush,
    modifier: Modifier,
) {
    val radius = with(LocalDensity.current) { Radii.XS.toPx() }
    Box(modifier.drawBehind { drawRoundRect(brush, cornerRadius = CornerRadius(radius)) })
}

@Composable
private fun shimmerBrush(): Brush {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    if (ReplylyTheme.motion.reduced) return SolidColor(base)
    val highlight = MaterialTheme.colorScheme.surfaceContainerLow
    val width =
        LocalWindowInfo.current.containerSize.width
            .toFloat()
    // A frame loop, not a tween: a loop never runs with reduced motion (returned above), and the
    // infinite-frame call lets tests settle while it runs.
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var start = -1L
        while (true) {
            withInfiniteAnimationFrameMillis { now ->
                if (start < 0) start = now
                progress = ((now - start) % SHIMMER_MILLIS) / SHIMMER_MILLIS.toFloat()
            }
        }
    }
    val x = -width + progress * width * 2
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(x, 0f),
        end = Offset(x + width / 2, 0f),
    )
}

/**
 * Fades and slides [content] in after the stagger delay of [index] (all at once with reduced
 * motion). A new [key] (a new generation) plays the entrance again.
 */
@Composable
internal fun StaggeredItem(
    index: Int,
    key: Any,
    content: @Composable () -> Unit,
) {
    val motion = ReplylyTheme.motion
    val visible = remember(key) { MutableTransitionState(false).apply { targetState = true } }
    val delay = motion.staggerDelay(index)
    AnimatedVisibility(
        visibleState = visible,
        enter =
            fadeIn(motion.tween(MotionDurations.LONG, MotionEasing.Enter, delay)) +
                slideInVertically(motion.tween(MotionDurations.LONG, MotionEasing.Enter, delay)) { it / 4 },
    ) {
        content()
    }
}

/**
 * One suggestion. Tap inserts (with a brief highlight), long-press copies. The haptics play when
 * the insert or copy succeeded (see `ComposeReplyPanel`), not on press.
 * TalkBack reads the intent and the text once, with "Insert" and "Copy" actions.
 *
 * On X ([lengthLimit] set) a counter under the text shows the weighted length as X counts it
 * ("212/280", [LengthCounter]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SuggestionCard(
    suggestion: Suggestion,
    highlighted: Boolean,
    enabled: Boolean,
    onInsert: () -> Unit,
    onCopy: () -> Unit,
    lengthLimit: Int? = null,
) {
    val motion = ReplylyTheme.motion
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (highlighted) colors.primaryContainer else colors.surfaceContainerHigh,
        motion.tween(MotionDurations.SHORT),
        label = "card highlight",
    )
    val content = if (highlighted) colors.onPrimaryContainer else colors.onSurface
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .combinedClickable(
                enabled = enabled,
                onClickLabel = "Insert",
                onLongClickLabel = "Copy",
                onLongClick = onCopy,
                onClick = onInsert,
            ).semantics(mergeDescendants = true) {
                if (enabled) {
                    customActions =
                        listOf(
                            CustomAccessibilityAction("Insert") {
                                onInsert()
                                true
                            },
                            CustomAccessibilityAction("Copy") {
                                onCopy()
                                true
                            },
                        )
                }
            }.padding(Spacing.M),
        verticalArrangement = Arrangement.spacedBy(Spacing.XS),
    ) {
        IntentChip(suggestion.intent)
        // No maxLines: long Hinglish replies wrap in full, the body scrolls.
        Text(suggestion.text, style = MaterialTheme.typography.bodyMedium, color = content)
        if (lengthLimit != null) {
            LengthCounter(suggestion, lengthLimit, Modifier.align(Alignment.End))
        }
    }
}

/**
 * "212/280": the card's weighted length ([XLength]) against the X post length. In the error colour
 * when the card was trimmed to fit or sits at the limit. TalkBack reads "212 of 280 characters",
 * plus "trimmed to fit".
 */
@Composable
internal fun LengthCounter(
    suggestion: Suggestion,
    limit: Int,
    modifier: Modifier = Modifier,
) {
    val used = XLength.weighted(suggestion.text)
    val warn = suggestion.trimmed || used >= limit
    val description = "$used of $limit characters" + if (suggestion.trimmed) ", trimmed to fit" else ""
    Text(
        "$used/$limit",
        style = MaterialTheme.typography.labelSmall,
        color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { contentDescription = description },
    )
}

/** "1 reply was over 280 and was left out." under fewer than 3 X cards. */
internal fun droppedNotice(
    dropped: Int,
    limit: Int,
): String =
    if (dropped == 1) {
        "1 reply was over $limit characters and was left out. Regenerate for more."
    } else {
        "$dropped replies were over $limit characters and were left out. Regenerate for more."
    }

/** The intent as a small coloured tag ("Agree", "Decline", "Ask", or the model's own word). */
@Composable
internal fun IntentChip(intent: String) {
    val pair = ReplylyTheme.colors.forIntent(intent)
    Text(
        intentLabel(intent),
        style = MaterialTheme.typography.labelMedium,
        color = pair.content,
        modifier =
            Modifier
                .clip(MaterialTheme.shapes.extraSmall)
                .background(pair.container)
                .padding(horizontal = Spacing.S, vertical = Spacing.XXS),
    )
}

/** "agree" → "Agree": sentence case, as the design asks. */
internal fun intentLabel(intent: String): String = intent.trim().replaceFirstChar { it.titlecase() }.ifEmpty { "Reply" }

/** A friendly error: an icon for its group, the fixed title and detail, and one action. */
@Composable
internal fun ErrorState(
    error: PanelError,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surfaceContainerHigh)
            .padding(horizontal = Spacing.L, vertical = Spacing.L),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        Column(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.XS),
        ) {
            Box(
                Modifier.size(ERROR_ICON_BUBBLE).clip(CircleShape).background(colors.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(error.kind.icon()),
                    contentDescription = null,
                    tint = colors.onTertiaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(Spacing.XS))
            Text(
                error.title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            error.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        val (label, icon, onClick) =
            when (error.action) {
                ErrorAction.OpenSettings -> Triple("Open settings", R.drawable.ic_panel_tune, onOpenSettings)
                ErrorAction.Retry -> Triple("Try again", R.drawable.ic_panel_refresh, onRetry)
            }
        Button(onClick = onClick, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(label)
        }
    }
}

private val ERROR_ICON_BUBBLE: Dp = 48.dp

/**
 * Nothing needs an answer (issue #26); the subtitle says why (issue #38). Opening the panel in
 * this state made no LLM call; the button makes exactly one, for 3 follow-ups.
 */
@Composable
internal fun NothingToReplyState(
    reason: NothingToReplyReason,
    onSuggestFollowUps: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surfaceContainerHigh)
            .padding(horizontal = Spacing.L, vertical = Spacing.L),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        Column(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.XS),
        ) {
            Box(
                Modifier.size(ERROR_ICON_BUBBLE).clip(CircleShape).background(colors.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_panel_check),
                    contentDescription = null,
                    tint = colors.onSecondaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(Spacing.XS))
            Text(
                NOTHING_TO_REPLY_TITLE,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                reason.subtitle(),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Button(onClick = onSuggestFollowUps, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
            Icon(
                painterResource(R.drawable.ic_sparkle),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(SUGGEST_FOLLOW_UPS)
        }
    }
}

internal const val NOTHING_TO_REPLY_TITLE = "Nothing to reply to"
internal const val SUGGEST_FOLLOW_UPS = "Suggest follow-ups anyway"

internal fun NothingToReplyReason.subtitle(): String =
    when (this) {
        NothingToReplyReason.MyMessageLast -> "Your message is the last one in this chat."
        NothingToReplyReason.TheirMediaAfterMine -> "Nothing after your last message needs an answer."
        NothingToReplyReason.NoMessageOfMine -> "No messages to reply to."
    }

private fun ErrorKind.icon(): Int =
    when (this) {
        ErrorKind.Key -> R.drawable.ic_panel_key
        ErrorKind.Settings -> R.drawable.ic_panel_tune
        ErrorKind.Limit -> R.drawable.ic_panel_hourglass
        ErrorKind.Connection -> R.drawable.ic_panel_cloud_off
        ErrorKind.Storage -> R.drawable.ic_panel_lock
        ErrorKind.Reply -> R.drawable.ic_panel_error
    }

/** The small "Copied" pill after a long-press. */
@Composable
internal fun CopiedPill(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalContentColor provides colors.inverseOnSurface) {
        Row(
            modifier
                .clip(CircleShape)
                .background(colors.inverseSurface)
                .padding(horizontal = Spacing.M, vertical = Spacing.S)
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "Copied"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_panel_check), contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.XS))
            Text("Copied", style = MaterialTheme.typography.labelLarge)
        }
    }
}
