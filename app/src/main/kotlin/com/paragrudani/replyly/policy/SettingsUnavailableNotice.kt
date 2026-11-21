package com.paragrudani.replyly.policy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.paragrudani.replyly.R

/** Tells the user why Replyly is off everywhere when its settings could not be loaded. */
object SettingsUnavailableNotice {
    fun show(context: Context) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, R.string.settings_unavailable_paused, Toast.LENGTH_LONG).show()
        }
    }
}
