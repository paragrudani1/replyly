package com.paragrudani.replyly.panel

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/** Long-press on a card copies its text here instead of inserting it. */
fun interface SuggestionClipboard {
    /** True when the text is on the clipboard. May throw; the caller shows a fixed notice. */
    fun copy(text: String): Boolean

    companion object {
        /** For states built without a clipboard (tests that do not copy). */
        val Unavailable = SuggestionClipboard { false }
    }
}

/**
 * The system clipboard. The clip is marked sensitive (`EXTRA_IS_SENSITIVE`), so Android's
 * clipboard preview and keyboards' clipboard strips hide its text: a reply is chat content.
 */
class AndroidSuggestionClipboard(
    context: Context,
) : SuggestionClipboard {
    private val manager = context.getSystemService(ClipboardManager::class.java)

    override fun copy(text: String): Boolean {
        val manager = manager ?: return false
        val clip = ClipData.newPlainText(CLIP_LABEL, text)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        manager.setPrimaryClip(clip)
        return true
    }

    companion object {
        /** The clip's label: fixed, never chat text. */
        const val CLIP_LABEL = "Replyly reply"
    }
}
