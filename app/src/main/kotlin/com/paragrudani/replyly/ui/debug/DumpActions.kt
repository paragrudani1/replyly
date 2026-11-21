package com.paragrudani.replyly.ui.debug

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.PersistableBundle
import android.provider.MediaStore
import android.provider.Settings

/** How a dump leaves the app. Each one is a deliberate tap by the user on the debug screen. */
object DumpActions {
    /**
     * Share and copy pass the JSON through a Binder transaction (about 1 MB, two bytes per char).
     * Bigger dumps must be saved to Downloads instead.
     */
    const val MAX_TEXT_CHARS = 250_000

    fun fitsAsText(json: String): Boolean = json.length <= MAX_TEXT_CHARS

    fun share(
        context: Context,
        fileName: String,
        json: String,
    ) {
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, fileName)
                .putExtra(Intent.EXTRA_TEXT, json)
        context.startActivity(Intent.createChooser(send, "Share $fileName"))
    }

    /** Unscrambled dumps are flagged sensitive, so Android hides them in the clipboard preview. */
    fun copy(
        context: Context,
        fileName: String,
        json: String,
        sensitive: Boolean,
    ) {
        val clip = ClipData.newPlainText(fileName, json)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    /** Writes to Downloads/Replyly. Blocking IO: call it off the main thread. Returns false on failure. */
    fun saveToDownloads(
        context: Context,
        fileName: String,
        json: String,
    ): Boolean {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Replyly")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: return false
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            true
        } catch (e: java.io.IOException) {
            resolver.delete(uri, null, null)
            false
        }
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
