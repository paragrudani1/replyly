package com.paragrudani.replyly.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

// Owned by ticket #7.

@Serializable
object OnboardingRoute

fun NavGraphBuilder.onboardingDestination(onFinished: () -> Unit) {
    composable<OnboardingRoute> { OnboardingScreen(onFinished = onFinished) }
}

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Set up Replyly", style = MaterialTheme.typography.headlineMedium)
            Text("Onboarding steps go here (ticket #7).")
            Button(onClick = onFinished) { Text("Continue") }
        }
    }
}
