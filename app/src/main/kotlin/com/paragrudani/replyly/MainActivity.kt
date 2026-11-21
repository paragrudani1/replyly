package com.paragrudani.replyly

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.paragrudani.replyly.ui.ReplylyNavHost
import com.paragrudani.replyly.ui.theme.ReplylyTheme

class MainActivity : ComponentActivity() {
    /** Bumped for every intent that asks for the settings screen; the NavHost reacts to changes. */
    private var openSettingsRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity (rotation, process restore) keeps its back stack: do not jump again.
        if (savedInstanceState == null) consume(intent)
        setContent {
            ReplylyTheme {
                ReplylyNavHost(openSettingsRequest = openSettingsRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    private fun consume(intent: Intent?) {
        if (!opensSettings(intent)) return
        intent?.removeExtra(EXTRA_DESTINATION)
        openSettingsRequest++
    }

    companion object {
        private const val EXTRA_DESTINATION = "com.paragrudani.replyly.extra.DESTINATION"
        private const val DESTINATION_SETTINGS = "settings"

        /**
         * Opens Replyly on its settings screen, from outside an Activity (the overlay panel). An
         * already running MainActivity gets it in [onNewIntent] instead of a second copy.
         */
        fun settingsIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_DESTINATION, DESTINATION_SETTINGS)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )

        internal fun opensSettings(intent: Intent?): Boolean =
            intent?.getStringExtra(EXTRA_DESTINATION) == DESTINATION_SETTINGS
    }
}
