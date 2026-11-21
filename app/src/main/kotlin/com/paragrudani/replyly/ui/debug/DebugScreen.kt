package com.paragrudani.replyly.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

// Owned by ticket #2. Hidden: reached by long-pressing the settings title.

@Serializable
object DebugRoute

fun NavGraphBuilder.debugDestination(onBack: () -> Unit) {
    composable<DebugRoute> { DebugScreen(onBack = onBack) }
}

@Composable
fun DebugScreen(onBack: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Debug: screen tree dump", style = MaterialTheme.typography.headlineMedium)
            Text("Tree dump (ScreenSnapshotJson) goes here (ticket #2).")
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}
