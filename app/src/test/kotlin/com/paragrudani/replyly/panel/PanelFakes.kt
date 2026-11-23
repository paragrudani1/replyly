package com.paragrudani.replyly.panel

import com.paragrudani.replyly.feedback.FeedbackEntry
import com.paragrudani.replyly.feedback.FeedbackLog
import com.paragrudani.replyly.history.ContactNoteStore
import com.paragrudani.replyly.history.HistoryRepository
import com.paragrudani.replyly.history.StoredMessage
import com.paragrudani.replyly.insert.InsertResult
import com.paragrudani.replyly.insert.ReplyInserter
import com.paragrudani.replyly.llm.PromptMessage
import com.paragrudani.replyly.llm.ReplyGenerator
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.llm.SuggestionRequest
import com.paragrudani.replyly.llm.SuggestionRequestAssembler
import com.paragrudani.replyly.llm.SuggestionResult
import com.paragrudani.replyly.llm.TokenUsage
import com.paragrudani.replyly.parser.ChatKey
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.parser.ParsedChat
import com.paragrudani.replyly.parser.x.XMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.milliseconds

val whatsAppChat =
    ParsedChat(
        packageName = "com.whatsapp",
        title = "Riya",
        isGroup = false,
        messages = listOf(ChatMessage("Riya", "kal mil rahe?", "6:42 pm", isMe = false)),
        historyApplies = true,
    )

/** My message is the last one: recent (no date known) and not a question, so nothing to reply to. */
val myMessageLastChat =
    whatsAppChat.copy(
        messages = whatsAppChat.messages + ChatMessage(null, "haan pakka, 6 baje", "6:50 pm", isMe = true),
    )

val genericChat = whatsAppChat.copy(packageName = "com.example.mail", title = "Inbox", historyApplies = false)

val threeSuggestions =
    listOf(
        Suggestion("agree", "Haan pakka, 6 baje milte hain"),
        Suggestion("decline", "Kal nahi ho payega yaar"),
        Suggestion("ask", "Kitne baje?"),
    )

fun success(
    suggestions: List<Suggestion> = threeSuggestions,
    model: String = "test/model",
) = SuggestionResult.Success(
    suggestions = suggestions,
    usage = TokenUsage(promptTokens = 100, completionTokens = 20, cacheReadTokens = 64),
    latency = 1_200.milliseconds,
    model = model,
    promptSent = "PROMPT",
    analysis = "ANALYSIS",
)

/** The fixed "now" of [FakeAssembler]: Sunday 22 Nov 2026, 7:05 pm in Kolkata. */
val panelNow: ZonedDateTime = ZonedDateTime.of(2026, 11, 22, 19, 5, 0, 0, ZoneId.of("Asia/Kolkata"))

/** Builds the request from the chat on screen; messages have no dates (like a generic app). */
class FakeAssembler(
    private val now: ZonedDateTime = panelNow,
) : SuggestionRequestAssembler {
    val calls = mutableListOf<Pair<ParsedChat, String?>>()
    var failWith: Exception? = null

    /** Replaces the chat's messages when set (a history read that differs from the screen). */
    var historyOverride: List<PromptMessage>? = null

    override suspend fun assemble(
        chat: ParsedChat,
        instruction: String?,
    ): SuggestionRequest {
        calls += chat to instruction
        failWith?.let { throw it }
        return SuggestionRequest(
            packageName = chat.packageName,
            appLabel = "WhatsApp",
            chatTitle = chat.title,
            isGroup = chat.isGroup,
            history = historyOverride ?: chat.messages.map { PromptMessage(it) },
            aboutMe = "",
            contactNote = null,
            styleExamples = emptyList(),
            instruction = instruction,
            now = now,
            x = chat.x,
        )
    }
}

/** Takes [GENERATE_MILLIS] of virtual time, then returns the next queued result (or the last one again). */
class FakeGenerator(
    vararg results: SuggestionResult,
) : ReplyGenerator {
    private val queue = ArrayDeque(results.toList().ifEmpty { listOf(success()) })
    val requests = mutableListOf<SuggestionRequest>()
    val calls: Int get() = requests.size

    override suspend fun generate(request: SuggestionRequest): SuggestionResult {
        requests += request
        delay(GENERATE_MILLIS)
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }

    companion object {
        const val GENERATE_MILLIS = 1_000L
    }
}

/** Returns [result], after [delayMillis] of virtual time (a slow cross-process insert when above 0). */
class FakeInserter(
    var result: InsertResult = InsertResult.Inserted,
    var delayMillis: Long = 0,
) : ReplyInserter {
    val inserted = mutableListOf<String>()

    override suspend fun insert(text: String): InsertResult {
        inserted += text
        if (delayMillis > 0) delay(delayMillis)
        return result
    }
}

class FakeFeedbackLog : FeedbackLog {
    data class Recorded(
        val chatKey: ChatKey,
        val model: String,
        val prompt: String,
        val suggestions: List<Suggestion>,
        val analysis: String? = null,
        val promptVersion: Int = 2,
        val panelId: String? = null,
        val cacheReadTokens: Int? = null,
        val xMode: XMode? = null,
    )

    val recorded = mutableListOf<Recorded>()
    val marked = mutableListOf<Pair<Long, Int>>()
    var failRecord = false

    override suspend fun record(
        chatKey: ChatKey,
        model: String,
        prompt: String,
        suggestions: List<Suggestion>,
        analysis: String?,
        promptVersion: Int,
        panelId: String?,
        cacheReadTokens: Int?,
        xMode: XMode?,
    ): Long {
        if (failRecord) throw IllegalStateException("db closed")
        recorded +=
            Recorded(chatKey, model, prompt, suggestions, analysis, promptVersion, panelId, cacheReadTokens, xMode)
        return 40L + recorded.size
    }

    override suspend fun markInserted(
        entryId: Long,
        index: Int,
    ) {
        marked += entryId to index
    }

    override suspend fun attachFinalMessage(
        chatKey: ChatKey,
        text: String,
    ): Boolean = false

    override fun observeRecent(limit: Int): Flow<List<FeedbackEntry>> = emptyFlow()

    override suspend fun purgeOlderThan(cutoff: Instant): Int = 0
}

class FakeNoteStore(
    initial: Map<ChatKey, String> = emptyMap(),
) : ContactNoteStore {
    val notes = MutableStateFlow(initial)
    var failSet = false
    var failGet = false

    override suspend fun get(chatKey: ChatKey): String? {
        if (failGet) throw IllegalStateException("db closed")
        return notes.value[chatKey]
    }

    override suspend fun set(
        chatKey: ChatKey,
        note: String,
    ) {
        if (failSet) throw IllegalStateException("db closed")
        notes.value = if (note.isBlank()) notes.value - chatKey else notes.value + (chatKey to note)
    }

    override fun observe(chatKey: ChatKey): Flow<String?> = notes.map { it[chatKey] }
}

class FakeHistory : HistoryRepository {
    val deleted = mutableListOf<ChatKey>()
    var failDelete = false

    override suspend fun merge(chat: ParsedChat): Int = 0

    override suspend fun lastMessages(
        chatKey: ChatKey,
        limit: Int,
    ): List<StoredMessage> = emptyList()

    override suspend fun recentOwnMessagesInOtherChats(
        packageName: String,
        excluding: ChatKey,
        limit: Int,
    ): List<StoredMessage> = emptyList()

    override suspend fun deleteChat(chatKey: ChatKey) {
        if (failDelete) throw IllegalStateException("db closed")
        deleted += chatKey
    }

    override suspend fun purgeOlderThan(cutoff: Instant): Int = 0
}
