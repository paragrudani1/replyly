package com.paragrudani.replyly.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.paragrudani.replyly.R
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import com.paragrudani.replyly.ui.theme.SheetShape
import com.paragrudani.replyly.ui.theme.Spacing
import kotlinx.coroutines.launch

/** [WhatsNew] for the installed app: its versionCode and whether this run is a fresh install. */
fun whatsNewFor(
    context: Context,
    hasNotes: Boolean,
): WhatsNew {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return WhatsNew(
        store = PrefsLastSeenVersionStore(context),
        currentVersion = info.longVersionCode,
        freshInstall = info.firstInstallTime == info.lastUpdateTime,
        hasNotes = hasNotes,
    )
}

/** The "What's new" bottom sheet. Swipe down, tap outside, Back or "Got it" closes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(
    title: String,
    notes: List<String>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = SheetShape) {
        WhatsNewContent(
            title = title,
            notes = notes,
            onDone = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } },
        )
    }
}

/** The sheet's content, stateless (screenshot tests render this). */
@Composable
fun WhatsNewContent(
    title: String,
    notes: List<String>,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(start = Spacing.XL, end = Spacing.XL, bottom = Spacing.XL),
        verticalArrangement = Arrangement.spacedBy(Spacing.L),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
            Icon(
                painterResource(R.drawable.ic_sparkle),
                contentDescription = null,
                tint = ReplylyTheme.colors.accent,
                modifier = Modifier.size(24.dp),
            )
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
            notes.forEach { note ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                    Icon(
                        painterResource(R.drawable.ic_sparkle),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = Spacing.XS).size(14.dp),
                    )
                    Text(note, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = Spacing.S)) { Text("Got it") }
    }
}
