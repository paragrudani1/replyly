package com.paragrudani.replyly.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.paragrudani.replyly.cost.CostStatus
import com.paragrudani.replyly.ui.theme.MotionDurations
import com.paragrudani.replyly.ui.theme.MotionEasing
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import com.paragrudani.replyly.ui.theme.Spacing
import java.text.NumberFormat

/** Share of today's limit used, 0..1 (a limit of 0 counts as full). */
fun CostStatus.dailyProgress(): Float =
    if (dailyRequestLimit <= 0) 1f else (requestsToday.toFloat() / dailyRequestLimit).coerceIn(0f, 1f)

/**
 * Today's requests as a ring against the daily limit, plus this month's tokens. The ring fills
 * with [ReplylyTheme.motion] (it snaps when animations are off) and turns the error colour at
 * the limit. TalkBack reads "Requests today, 120 of 200" as one progress node.
 */
@Composable
fun UsageCard(
    usage: CostStatus,
    modifier: Modifier = Modifier,
) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val today = numbers.format(usage.requestsToday)
    val limit = numbers.format(usage.dailyRequestLimit)
    val progress = usage.dailyProgress()
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.L, vertical = Spacing.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.L),
    ) {
        UsageRing(
            progress = progress,
            atLimit = usage.limitReached,
            modifier =
                Modifier.clearAndSetSemantics {
                    contentDescription = "Requests today, $today of $limit"
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                },
        ) {
            Text(
                "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) {
            },
            verticalArrangement = Arrangement.spacedBy(Spacing.XXS),
        ) {
            Text("$today of $limit requests today", style = MaterialTheme.typography.bodyLarge)
            if (usage.limitReached) {
                Text(
                    "Daily limit reached. It resets at midnight.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "${numbers.format(usage.totalTokensThisMonth)} tokens this month",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Prompt ${numbers.format(usage.promptTokensThisMonth)} · " +
                    "completion ${numbers.format(usage.completionTokensThisMonth)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A thin progress ring with [content] in the middle. [progress] is 0..1. */
@Composable
fun UsageRing(
    progress: Float,
    atLimit: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    strokeWidth: Dp = 6.dp,
    content: @Composable () -> Unit = {},
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = ReplylyTheme.motion.tween(MotionDurations.LONG, MotionEasing.Standard),
        label = "usage ring",
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill: Color = if (atLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            val inset = strokeWidth.toPx() / 2
            val arcSize =
                androidx.compose.ui.geometry
                    .Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
            val topLeft =
                androidx.compose.ui.geometry
                    .Offset(inset, inset)
            drawArc(
                track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            if (animated > 0f) {
                drawArc(
                    fill,
                    -90f,
                    360f * animated,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
            }
        }
        content()
    }
}
