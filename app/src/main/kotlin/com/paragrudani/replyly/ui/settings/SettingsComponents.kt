package com.paragrudani.replyly.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.paragrudani.replyly.R
import com.paragrudani.replyly.ui.theme.MinTouchTarget
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import com.paragrudani.replyly.ui.theme.Spacing

/** A section title above its group: titleMedium in primary, a TalkBack heading. */
@Composable
fun SettingsSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = Spacing.XS, top = Spacing.S).semantics { heading() },
    )
}

/**
 * A grouped section: header, then its rows on one tonal surface (no shadow, DESIGN.md). Rows set
 * their own padding; use [SettingsGroupPadding] for free content such as text fields.
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        SettingsSectionHeader(title)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = Spacing.XS), content = content)
        }
    }
}

/** Padding for non-row content inside a [SettingsSection] (fields, cards). */
@Composable
fun SettingsGroupPadding(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.L, vertical = Spacing.S),
        verticalArrangement = Arrangement.spacedBy(Spacing.S),
        content = content,
    )
}

/** Hairline between rows of one section, inset to the text. */
@Composable
fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = Spacing.L),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** A tappable row that opens something: title, optional supporting text, chevron. At least 48 dp tall. */
@Composable
fun SettingsNavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    onClickLabel: String? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
                .padding(horizontal = Spacing.L, vertical = Spacing.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        RowTexts(title, supporting, Modifier.weight(1f))
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** A row showing a value that cannot be changed (the version). */
@Composable
fun SettingsInfoRow(
    title: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = Spacing.L, vertical = Spacing.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A whole-row switch: one touch target and one TalkBack stop ("label, switch, off"). With
 * [haptic], toggling gives a light on/off tick.
 */
@Composable
fun SettingsSwitchRow(
    label: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    haptic: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .toggleable(value = checked, role = Role.Switch) { value ->
                    if (haptic) {
                        haptics.performHapticFeedback(
                            if (value) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
                        )
                    }
                    onCheckedChange(value)
                }.padding(horizontal = Spacing.L, vertical = Spacing.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        RowTexts(label, supporting, Modifier.weight(1f))
        // The row handles the toggle, so the whole line is one touch target and one a11y node.
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun RowTexts(
    title: String,
    supporting: String?,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.XXS)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** An outlined text field whose supporting line shows [error] when set, else [supporting]. */
@Composable
fun ValidatedField(
    label: String,
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    supporting: String? = null,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = keyboardType,
                capitalization = capitalization,
                autoCorrectEnabled = !singleLine,
            ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A small illustration with a title and one line of help, centred. The illustration is a
 * single-colour vector tinted [MaterialTheme.colorScheme.primary]; [sparkle] adds the marigold
 * sparkle on its top right (the one accent moment). Decorative: TalkBack reads the texts only.
 */
@Composable
fun EmptyState(
    illustration: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    sparkle: Boolean = false,
    /** Smaller type, for an empty state inside a section. */
    compact: Boolean = false,
) {
    Column(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        Box {
            Icon(
                painterResource(illustration),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(width = size * 1.25f, height = size),
            )
            if (sparkle) {
                Icon(
                    painterResource(R.drawable.ic_sparkle),
                    contentDescription = null,
                    tint = ReplylyTheme.colors.accent,
                    modifier = Modifier.align(Alignment.TopEnd).size(size * 0.3f),
                )
            }
        }
        val type = MaterialTheme.typography
        Text(title, style = if (compact) type.titleSmall else type.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = if (compact) type.bodySmall else type.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
