package com.paragrudani.replyly.insert

import com.paragrudani.replyly.service.AccessibilityHostHolder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fixed failure reasons. They never contain chat text. */
object InsertFailures {
    const val REFUSED = "The app did not accept the text"
    const val SERVICE_OFF = "Replyly's accessibility service is off"
}

/**
 * Inserts a suggestion with ACTION_SET_TEXT (ticket #1). The overlay calls [rememberTarget] when
 * the user taps the button; [insert] finds that field again ([InputFieldFinder]), replaces its
 * text (decision 2026-11-21: the suggestion replaces the field's content) and puts the cursor at
 * the end. It never clicks, never sends, never presses Enter.
 *
 * Results: [InsertResult.NoFocusedField] when the field is gone, [InsertResult.Failed] with an
 * [InsertFailures] reason when the app refused the text or the service is off. A refused cursor
 * move still counts as inserted: the text is in.
 */
class AccessibilityReplyInserter(
    private val source: () -> InputFieldSource?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ReplyInserter {
    @Volatile
    private var target: InputFieldRef? = null

    fun rememberTarget(ref: InputFieldRef) {
        target = ref
    }

    override suspend fun insert(text: String): InsertResult =
        withContext(dispatcher) {
            val ref = target ?: return@withContext InsertResult.NoFocusedField
            val fields = source() ?: return@withContext InsertResult.Failed(InsertFailures.SERVICE_OFF)
            val field = InputFieldFinder.find(ref, fields) ?: return@withContext InsertResult.NoFocusedField
            if (!field.setText(text)) return@withContext InsertResult.Failed(InsertFailures.REFUSED)
            field.setSelection(text.length, text.length)
            InsertResult.Inserted
        }

    companion object {
        /** Reads the live windows of the running accessibility service, if any. */
        fun create(hosts: AccessibilityHostHolder): AccessibilityReplyInserter =
            AccessibilityReplyInserter(
                source = {
                    hosts.host.value?.service?.let { service ->
                        AccessibilityInputFieldSource(windows = { service.windows })
                    }
                },
            )
    }
}
