package com.paragrudani.replyly.ui.theme

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.paragrudani.replyly.R

/**
 * The "replyly" wordmark: lower-case Figtree SemiBold, tracked tight, with the marigold sparkle
 * sitting where a tittle would be. TalkBack reads it as "Replyly". Size it with [style];
 * the sparkle scales with the font size.
 */
@Composable
fun ReplylyWordmark(
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineSmall,
    color: Color = MaterialTheme.colorScheme.onSurface,
    accent: Color = ReplylyTheme.colors.accent,
) {
    val sparkle = with(LocalDensity.current) { (style.fontSize * SPARKLE_SCALE).toDp() }
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = "Replyly" },
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "replyly",
            style = style.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.03).em),
            color = color,
        )
        Icon(
            painter = painterResource(R.drawable.ic_sparkle),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.offset(x = sparkle * 0.1f, y = sparkle * 0.15f).size(sparkle),
        )
    }
}

private const val SPARKLE_SCALE = 0.5f
