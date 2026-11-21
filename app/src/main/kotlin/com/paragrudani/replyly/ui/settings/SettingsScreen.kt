package com.paragrudani.replyly.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

// Owned by ticket #6.

@Serializable
object SettingsRoute

fun NavGraphBuilder.settingsDestination(
    onOpenOnboarding: () -> Unit,
    onOpenDebug: () -> Unit,
) {
    composable<SettingsRoute> { SettingsScreen(onOpenOnboarding = onOpenOnboarding, onOpenDebug = onOpenDebug) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    onOpenOnboarding: () -> Unit,
    onOpenDebug: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Hidden entry to the debug screen: long-press the title.
            Text(
                "Replyly settings",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onOpenDebug),
            )
            Text("Settings go here (ticket #6).")
            OutlinedButton(onClick = onOpenOnboarding) { Text("Setup steps") }
        }
    }
}
