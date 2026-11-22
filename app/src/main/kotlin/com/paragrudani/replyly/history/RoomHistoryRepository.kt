package com.paragrudani.replyly.history

import androidx.room.withTransaction
import com.paragrudani.replyly.history.db.ChatPositionEntity
import com.paragrudani.replyly.history.db.ContactNoteEntity
import com.paragrudani.replyly.history.db.HistoryDatabaseHandle
import com.paragrudani.replyly.history.db.MessageEntity
import com.paragrudani.replyly.parser.ChatKey
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.parser.ParsedChat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** What one merge changed. */
data class MergeOutcome(
    val inserted: Int,
    val edited: Int,
    /** See [MergePlan.newAtEnd]: new messages below the current segment, oldest first. */
    val newAtEnd: List<ChatMessage>,
) {
    companion object {
        val NONE = MergeOutcome(0, 0, emptyList())
    }
}

/** A merge that also says what was new. Passive capture uses it to find the message the user sent. */
fun interface HistoryReadMerger {
    suspend fun mergeRead(chat: ParsedChat): MergeOutcome
}

/**
 * [HistoryRepository] on the encrypted Room DB. Merge logic: [HistoryMerge].
 *
 * Every merge also stores the chat's current position ([MergePlan.currentRowId]): the row of the
 * read's last message. [lastMessages] and [recentOwnMessagesInOtherChats] read up to it, so a
 * block of old messages stored at the end after a gap is not taken for the latest messages.
 *
 * Dates: a message read with a date (from a separator) is stored exact; one without gets the local
 * date (in the clock's zone) of when it was first seen, marked approx. A later read that lines up
 * with an approx row and shows its date makes it exact ([MergePlan.metaUpdates]).
 *
 * Capture merges after every pause, so a merge first loads only the newest [tailSize] rows of the
 * chat; it loads the whole chat only when the read does not overlap them (see
 * [HistoryMerge.planAgainstTail]).
 */
class RoomHistoryRepository(
    private val handle: HistoryDatabaseHandle,
    private val clock: Clock,
    private val tailSize: Int = DEFAULT_TAIL_SIZE,
) : HistoryRepository,
    HistoryReadMerger {
    // Passive capture and the assembler may merge the same chat at once; one merge at a time.
    private val mergeLock = Mutex()

    override suspend fun merge(chat: ParsedChat): Int = mergeRead(chat).inserted

    override suspend fun mergeRead(chat: ParsedChat): MergeOutcome {
        if (!chat.historyApplies || chat.messages.isEmpty()) return MergeOutcome.NONE
        val db = handle.get()
        val dao = db.messages()
        val chatKey = chat.chatKey.encoded
        return mergeLock.withLock {
            db.withTransaction {
                val current = dao.currentRowId(chatKey)
                val instant = clock.instant()
                val today = LocalDate.ofInstant(instant, clock.zone)
                val tail = dao.newestInChat(chatKey, tailSize).asReversed().map { it.toRow() }
                val plan =
                    if (tail.size < tailSize) {
                        HistoryMerge.plan(tail, chat.messages, current, today) // The tail is the whole chat.
                    } else {
                        HistoryMerge.planAgainstTail(tail, chat.messages, current, today)
                            ?: HistoryMerge.plan(
                                dao.allInChat(chatKey).map { it.toRow() },
                                chat.messages,
                                current,
                                today,
                            )
                    }
                if (plan.isNoOp) {
                    // A re-read still moves the current position (a scroll inside saved rows).
                    plan.currentRowId
                        ?.takeIf { it != current }
                        ?.let { dao.setCurrent(ChatPositionEntity(chatKey, it)) }
                    return@withTransaction MergeOutcome.NONE
                }
                plan.moves.forEach { dao.move(it.id, it.position) }
                plan.gapsCleared.forEach { dao.clearGap(it) }
                plan.edits.forEach { edit ->
                    val m = edit.message
                    dao.edit(edit.id, m.sender, m.text, m.timeShown, MessageHash.of(m))
                    m.date?.let { dao.setExactDate(edit.id, it.toEpochDay()) }
                    if (m.repliesToMe) dao.markRepliesToMe(edit.id)
                }
                plan.metaUpdates.forEach { meta ->
                    meta.date?.let { dao.setExactDate(meta.id, it.toEpochDay()) }
                    if (meta.repliesToMe) dao.markRepliesToMe(meta.id)
                }
                val now = instant.toEpochMilli()
                val firstSeenDay = today.toEpochDay()
                val insertedIds =
                    dao.insert(
                        plan.inserts.map { insert ->
                            val m = insert.message
                            MessageEntity(
                                chatKey = chatKey,
                                packageName = chat.packageName,
                                position = insert.position,
                                gapBefore = insert.gapBefore,
                                isMe = m.isMe,
                                sender = m.sender,
                                text = m.text,
                                timeShown = m.timeShown,
                                hash = MessageHash.of(m),
                                firstSeenAt = now,
                                messageDate = m.date?.toEpochDay() ?: firstSeenDay,
                                dateIsApprox = m.date == null,
                                repliesToMe = m.repliesToMe,
                            )
                        },
                    )
                // The read's last message is a saved row, or else the last insert.
                dao.setCurrent(ChatPositionEntity(chatKey, plan.currentRowId ?: insertedIds.last()))
                MergeOutcome(plan.inserts.size, plan.edits.size, plan.newAtEnd)
            }
        }
    }

    override suspend fun lastMessages(
        chatKey: ChatKey,
        limit: Int,
    ): List<StoredMessage> =
        handle
            .get()
            .messages()
            .endingAtCurrent(chatKey.encoded, limit)
            .asReversed()
            .map { it.toStored() }

    override suspend fun recentOwnMessagesInOtherChats(
        packageName: String,
        excluding: ChatKey,
        limit: Int,
    ): List<StoredMessage> =
        handle
            .get()
            .messages()
            .newestOwnInOtherChats(packageName, excluding.encoded, limit)
            .asReversed()
            .map { it.toStored() }

    override suspend fun deleteChat(chatKey: ChatKey) {
        mergeLock.withLock {
            val db = handle.get()
            db.withTransaction {
                db.messages().deleteChat(chatKey.encoded)
                db.messages().deleteCurrent(chatKey.encoded)
            }
        }
    }

    override suspend fun purgeOlderThan(cutoff: Instant): Int =
        mergeLock.withLock {
            val db = handle.get()
            db.withTransaction {
                db.messages().markGapsBeforePurge(cutoff.toEpochMilli())
                db.messages().deleteFirstSeenBefore(cutoff.toEpochMilli())
            }
        }

    private fun MessageEntity.toRow() =
        StoredRow(id, position, toMessage(), gapBefore, messageDate?.let(LocalDate::ofEpochDay), dateIsApprox)

    /** The message as the merge sees it: only an exact date. */
    private fun MessageEntity.toMessage() =
        ChatMessage(
            sender = sender,
            text = text,
            timeShown = timeShown,
            isMe = isMe,
            date = messageDate?.takeUnless { dateIsApprox }?.let(LocalDate::ofEpochDay),
            repliesToMe = repliesToMe,
        )

    private fun MessageEntity.toStored() =
        StoredMessage(
            ChatKey.decode(chatKey),
            toMessage(),
            Instant.ofEpochMilli(firstSeenAt),
            date = messageDate?.let(LocalDate::ofEpochDay),
            dateIsApprox = dateIsApprox,
        )

    companion object {
        const val DEFAULT_TAIL_SIZE = 500
    }
}

/** [ContactNoteStore] in the same encrypted DB. */
class RoomContactNoteStore(
    private val handle: HistoryDatabaseHandle,
    private val clock: Clock,
) : ContactNoteStore {
    override suspend fun get(chatKey: ChatKey): String? = handle.get().contactNotes().get(chatKey.encoded)

    override suspend fun set(
        chatKey: ChatKey,
        note: String,
    ) {
        val dao = handle.get().contactNotes()
        if (note.isBlank()) {
            dao.delete(chatKey.encoded)
        } else {
            dao.upsert(ContactNoteEntity(chatKey.encoded, note, clock.millis()))
        }
    }

    override fun observe(chatKey: ChatKey): Flow<String?> =
        flow { emitAll(handle.get().contactNotes().observe(chatKey.encoded)) }.distinctUntilChanged()
}
