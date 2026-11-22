package com.paragrudani.replyly.parser.telegram

import com.paragrudani.replyly.parser.ExplainingChatParser
import com.paragrudani.replyly.parser.ParseReport
import com.paragrudani.replyly.parser.ParsedChat
import com.paragrudani.replyly.parser.TargetApps
import com.paragrudani.replyly.parser.generic.ChatAssembler
import com.paragrudani.replyly.parser.generic.ChatDates
import com.paragrudani.replyly.parser.generic.ChatLayout
import com.paragrudani.replyly.parser.generic.ChatText
import com.paragrudani.replyly.parser.generic.GeometryReader
import com.paragrudani.replyly.parser.generic.GroupHint
import com.paragrudani.replyly.parser.generic.RawBubble
import com.paragrudani.replyly.parser.generic.ScreenTree
import com.paragrudani.replyly.parser.generic.Side
import com.paragrudani.replyly.screen.ScreenSnapshot
import java.time.LocalDate
import java.time.ZoneId

/**
 * Telegram draws each message as one custom full-width cell with no view ids, so cell bounds say
 * nothing about the side. The cell's accessibility description does (format unverified, see
 * docs/DEVICE_CHECKLIST.md steps 6.17-6.21):
 *
 * ```
 * [Sender name]            (groups, incoming only; maybe left out of a follow-up cell)
 * [Forwarded from X]
 * message text (one or more lines)
 * Sent today at 6:42 PM, Read      |  Received today at 6:42 PM
 * ```
 *
 * "Sent" = me. Cells without that last line (date headers, "Asha joined", "Unread messages") are not
 * messages. If no cell has it, the screen goes through [GeometryReader] instead.
 *
 * Dates: the last line names the day ("today", "yesterday", "Nov 19", "21.11.25"), which is the
 * message's own date. A cell whose line has none takes the date of the date header cell above it
 * ("Today", "November 19"), as on WhatsApp. Any other short line in the list that is not a cell
 * counts as a header too: one that is not a readable date ("Asha joined", or a date format
 * [ChatDates] does not know) ends the date above it, so the cells below get none rather than the
 * previous day's. Relative names resolve against the snapshot's capture day in [zone], and
 * [ChatAssembler.checkDates] drops every date of a screen whose dates do not add up. Reply
 * headers are not in the description as far as known: [CellDescription] never sets "replies to
 * me" (DEVICE_CHECKLIST.md 13).
 */
class TelegramParser(
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ExplainingChatParser {
    override val packages: Set<String> = setOf(TargetApps.TELEGRAM)

    override fun explain(snapshot: ScreenSnapshot): ParseReport {
        val tree = ScreenTree(snapshot)
        val layout = ChatLayout.detect(tree)
        val hint = ChatText.groupHint(layout.subtitle)
        val isGroupBySubtitle = hint == GroupHint.GROUP
        val now = snapshot.capturedAt.atZone(zone).toLocalDateTime()
        val today = now.toLocalDate()
        // Screen order, not tree order: the chat list may lay its children out newest first.
        val inList = tree.shownNodes.filter { layout.list == null || layout.list.isAncestorOf(it) }
        val cellNodes =
            inList
                .mapNotNull { node ->
                    listOfNotNull(node.text, node.description)
                        .firstOrNull { CellDescription.parse(it, isGroup = false) != null }
                        ?.let { node to it }
                }.sortedWith(compareBy(ScreenTree.readingOrder) { it.first })
        val raws = cellNodes.map { it.second }
        val headers =
            inList
                .filter { node -> cellNodes.none { (cell, _) -> cell === node || cell.isAncestorOf(node) } }
                .mapNotNull { node ->
                    listOfNotNull(node.text, node.description)
                        .firstOrNull(::isHeaderText)
                        ?.let { node.bounds.top to ChatDates.resolve(it, today) }
                }.sortedBy { it.first }
        val headerDates = ChatAssembler.datesBelow(headers, cellNodes.map { it.first.bounds.top })
        val knownSenders =
            if (isGroupBySubtitle) {
                CellDescription.repeatedFirstLines(raws) +
                    listOfNotNull(ChatText.activeMember(layout.subtitle))
            } else {
                emptySet()
            }
        val cells =
            raws.mapIndexedNotNull { i, raw ->
                CellDescription.parse(raw, isGroupBySubtitle, knownSenders, today)?.let { cell ->
                    if (cell.date == null) cell.copy(date = headerDates[i]) else cell
                }
            }

        if (cells.isNotEmpty()) {
            val title = layout.title ?: return ParseReport(null, METHOD_DESCRIPTIONS, listOf("no chat title"))
            val checked =
                ChatAssembler.checkDates(
                    cells.map {
                        RawBubble(if (it.isMe) Side.RIGHT else Side.LEFT, it.sender, it.text, it.time, it.date)
                    },
                    headers.map { it.second },
                    now,
                )
            val chat = chat(snapshot, title, isGroupBySubtitle, checked.bubbles)
            val notes =
                listOf(
                    "${cells.size} message cells",
                    "group from subtitle: ${hint.name.lowercase()}",
                    "${cells.count { it.ownDate }} cells dated by their own text",
                    ChatAssembler.dateNote(headers.map { it.second }, chat.messages, checked.dropped),
                )
            return ParseReport(chat, METHOD_DESCRIPTIONS, notes)
        }

        if (layout.input ==
            null
        ) {
            return ParseReport(null, METHOD_GEOMETRY, listOf("no message cells and no input field"))
        }
        val title = layout.title ?: return ParseReport(null, METHOD_GEOMETRY, listOf("no chat title"))
        val result = GeometryReader(GeometryReader.Mode.FULL).read(tree, layout, hint, today)
        val checked = ChatAssembler.checkDates(result.bubbles, result.separators, now)
        val isGroup = isGroupBySubtitle || result.labelsFound
        val chat = chat(snapshot, title, isGroup, checked.bubbles)
        val notes =
            listOf(
                "no message cells: geometry fallback",
                "${result.units} rows, ${result.bubbles.size} bubbles, ${result.skipped} skipped",
                ChatAssembler.dateNote(result.separators, chat.messages, checked.dropped),
            )
        return ParseReport(chat, METHOD_GEOMETRY, notes)
    }

    /**
     * A list node that is not a message cell, with one short line: a date header ("Today",
     * "November 19"), or a service line ("Asha joined the group") that may be an unreadable header.
     * Either way it ends the date of the header above it (an unreadable one resolves to `null`).
     * The unread banner sits between messages of one day, so it does not.
     */
    private fun isHeaderText(text: String): Boolean {
        val s = ChatText.squash(text)
        return s.isNotEmpty() && '\n' !in text && s.length <= MAX_HEADER && !ChatText.isUnreadBanner(s)
    }

    private fun chat(
        snapshot: ScreenSnapshot,
        title: String,
        isGroup: Boolean,
        bubbles: List<RawBubble>,
    ) = ParsedChat(
        packageName = snapshot.packageName,
        title = title,
        isGroup = isGroup,
        messages = ChatAssembler.messages(bubbles, isGroup, oneToOneSender = title),
        historyApplies = true,
    )

    companion object {
        const val METHOD_DESCRIPTIONS = "telegram:cell-descriptions"
        const val METHOD_GEOMETRY = "telegram:geometry"

        /** The longest header line: "Wednesday, 19 November 2026" with room to spare. */
        private const val MAX_HEADER = 40
    }
}

/** One Telegram message cell, read from its accessibility text. */
internal data class CellDescription(
    val isMe: Boolean,
    val sender: String?,
    /** `null` when the cell has no text (a photo without caption). */
    val text: String?,
    val time: String?,
    /** The date in the status line, or else (set by the parser) the date header's above the cell. */
    val date: LocalDate? = null,
    /** [date] came from the status line itself. */
    val ownDate: Boolean = false,
) {
    companion object {
        private val status = Regex("""^(sent|received)\b(.*)$""", RegexOption.IGNORE_CASE)
        private val forwarded = Regex("""^forwarded from\b.*""", RegexOption.IGNORE_CASE)
        private const val MAX_NAME = 40
        private const val MAX_NAME_WORDS = 4

        private class Split(
            val isMe: Boolean,
            val body: List<String>,
            val time: String,
            val status: String,
        )

        private fun split(raw: String?): Split? {
            if (raw == null) return null
            val lines = raw.split('\n').map { it.trim() }.dropLastWhile { it.isEmpty() }
            if (lines.size < 2) return null
            val match = status.matchEntire(ChatText.squash(lines.last())) ?: return null
            val time = ChatText.findTime(lines.last()) ?: return null
            return Split(match.groupValues[1].equals("sent", ignoreCase = true), lines.dropLast(1), time, lines.last())
        }

        /**
         * `null` if [raw] is not a message cell. The status line must be the last line and contain a
         * time, so a message that merely starts with "Sent ..." is not mistaken for one.
         *
         * In a group, the first line of an incoming cell with two or more lines is the sender when it
         * is in [knownSenders] or looks like a name ([looksLikeName]). Otherwise it is text: Telegram
         * may leave the name out of a follow-up cell from the same person, whose sender then comes
         * from the run above it. "Forwarded from ..." is never a sender.
         *
         * With [today], the date in the status line resolves against it ([ChatDates.fromStatusLine]).
         */
        fun parse(
            raw: String?,
            isGroup: Boolean,
            knownSenders: Set<String> = emptySet(),
            today: LocalDate? = null,
        ): CellDescription? {
            val cell = split(raw) ?: return null
            var body = cell.body
            val first = ChatText.clean(body.first())
            val sender =
                first?.takeIf {
                    isGroup && !cell.isMe && body.size >= 2 && !isForwarded(it) &&
                        (it in knownSenders || looksLikeName(it))
                }
            if (sender != null) body = body.drop(1)
            body = body.filterNot(::isForwarded)
            val text = ChatText.clean(body.joinToString("\n"))?.takeUnless(ChatText::isDeletedPlaceholder)
            val date = today?.let { ChatDates.fromStatusLine(cell.status, it) }
            return CellDescription(cell.isMe, sender, text, cell.time, date, ownDate = date != null)
        }

        /** First lines that start two or more incoming multi-line cells: names seen elsewhere on screen. */
        fun repeatedFirstLines(raws: List<String>): Set<String> =
            raws
                .mapNotNull { raw ->
                    split(raw)?.takeIf { !it.isMe && it.body.size >= 2 }?.let { ChatText.clean(it.body.first()) }
                }.filterNot(::isForwarded)
                .groupingBy { it }
                .eachCount()
                .filterValues { it >= 2 }
                .keys

        /**
         * A short line of at most [MAX_NAME_WORDS] words, each starting with anything but a
         * lower-case letter, not ending like a sentence: "Asha", "Ravi K", "Mom ❤️", "+91 98765 43210".
         */
        fun looksLikeName(line: String): Boolean {
            val s = ChatText.squash(line)
            if (s.isEmpty() || s.length > MAX_NAME || s.last() in ".?!,:;") return false
            val words = s.split(' ')
            return words.size <= MAX_NAME_WORDS && words.all { !it.first().isLowerCase() }
        }

        private fun isForwarded(line: String): Boolean = forwarded.matches(ChatText.squash(line))
    }
}
