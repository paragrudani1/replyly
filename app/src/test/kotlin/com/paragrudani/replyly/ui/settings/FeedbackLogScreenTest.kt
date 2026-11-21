package com.paragrudani.replyly.ui.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FeedbackLogScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a storage error is shown in place of the empty-log hint`() {
        compose.setContent {
            FeedbackLogScreen(
                entries = emptyList(),
                error = SettingsViewModel.STORAGE_LOCKED,
                onClear = {},
                onBack = {},
            )
        }

        compose.onNodeWithText("Secure storage is locked. Try again.").assertExists()
        compose.onNodeWithText("No entries yet", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Clear log").assertIsNotEnabled()
    }

    @Test
    fun `without an error the empty-log hint shows`() {
        compose.setContent { FeedbackLogScreen(entries = emptyList(), error = null, onClear = {}, onBack = {}) }

        compose.onNodeWithText("No entries yet", substring = true).assertExists()
        compose.onNodeWithText("Secure storage is locked. Try again.").assertDoesNotExist()
    }
}
