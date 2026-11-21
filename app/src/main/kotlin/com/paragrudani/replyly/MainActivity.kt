package com.paragrudani.replyly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.paragrudani.replyly.ui.ReplylyNavHost
import com.paragrudani.replyly.ui.theme.ReplylyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReplylyTheme {
                ReplylyNavHost()
            }
        }
    }
}
