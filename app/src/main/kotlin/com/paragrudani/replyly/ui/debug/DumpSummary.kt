package com.paragrudani.replyly.ui.debug

import com.paragrudani.replyly.parser.ParseReport

/** The parse of a dump as plain lines, so Parag can see at a glance whether parsing worked. */
object DumpSummary {
    fun lines(report: ParseReport): List<String> =
        buildList {
            add("Parser: ${report.method}")
            val chat = report.chat
            if (chat == null) {
                add("Not recognised as a chat.")
            } else {
                add("Title: ${chat.title}")
                add("Chat key: ${chat.chatKey.encoded}")
                val kind = "Group: ${yesNo(chat.isGroup)} · History: ${yesNo(chat.historyApplies)}"
                add("$kind · ${chat.messages.size} messages")
            }
            if (report.notes.isNotEmpty()) add("Notes: ${report.notes.joinToString("; ")}")
            chat?.messages?.forEach { m ->
                val who = if (m.isMe) "→ me" else "← ${m.sender ?: "?"}"
                val time = m.timeShown?.let { " [$it]" }.orEmpty()
                add("$who$time ${m.text.replace("\n", "⏎")}")
            }
        }

    /** Class name only: exception messages can contain chat text. */
    fun crashed(error: Throwable): List<String> = listOf("Parser crashed: ${error::class.simpleName}")

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"
}
