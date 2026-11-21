package com.paragrudani.replyly.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.paragrudani.replyly.ui.theme.MinTouchTarget
import com.paragrudani.replyly.ui.theme.Spacing

/**
 * A single-choice list of model ids, one 48 dp radio row each. Stateless and reusable: the
 * provider profile editor (#18) passes its profile's models and the selected id.
 *
 * TalkBack: the list is a selectable group; each row reads "<id>, radio button, selected".
 *
 * @param models ids in display order; duplicates are shown once.
 * @param selected the current id, or null for none. An id not in [models] selects nothing.
 * @param label optional human name per id (default: the id itself).
 */
@Composable
fun ModelPickerList(
    models: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (String) -> String = { it },
) {
    Column(modifier.fillMaxWidth().selectableGroup()) {
        models.distinct().forEach { id ->
            val isSelected = id == selected
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTarget)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(id) })
                        .padding(horizontal = Spacing.L, vertical = Spacing.XS),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.M),
            ) {
                RadioButton(selected = isSelected, onClick = null)
                Text(
                    label(id),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
