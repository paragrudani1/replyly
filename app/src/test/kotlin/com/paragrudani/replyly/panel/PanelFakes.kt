package com.paragrudani.replyly.panel

import com.paragrudani.replyly.feedback.FeedbackEntry
import com.paragrudani.replyly.feedback.FeedbackLog
import com.paragrudani.replyly.history.ContactNoteStore
import com.paragrudani.replyly.history.HistoryRepository
import com.paragrudani.replyly.history.StoredMessage
import com.paragrudani.replyly.insert.InsertResult
import com.paragrudani.replyly.insert.ReplyInserter
import com.paragrudani.replyly.llm.ReplyGenerator
import com.paragrudani.replyly.llm.Suggestion
import com.paragrudani.replyly.llm.SuggestionRequest
import com.paragrudani.replyly.llm.SuggestionRequestAssembler
import com.paragrudani.replyly.llm.SuggestionResult
import com.paragrudani.replyly.llm.TokenUsage
import com.paragrudani.replyly.parser.ChatKey
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.parser.ParsedChat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

val whatsAppChat =
    ParsedChat(
        packageName = "com.whatsapp",
        title = "Riya",
        isGroup = false,
        messages = listOf(ChatMessage("Riya", "kal mil rahe?", "6:42 pm", isMe = false)),
        historyApplies = true,
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
    usage = TokenUsage(promptTokens = 100, completionTokens = 20),
    latency = 1_200.milliseconds,
    model = model,
    promptSent = "PROMPT",
)

class FakeAssembler : SuggestionRequestAssembler {
    val calls = mutableListOf<Pair<ParsedChat, String?>>()
    var failWith: Exception? = null

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
            history = chat.messages,
            aboutMe = "",
            contactNote = null,
            styleExamples = emptyList(),
            instruction = instruction,
        )
    }
}

/** Takes [GENERATE_MILLIS] of virtual time, then returns the next queued result (or the last one again). */
class FakeGenerator(
    vararg results: SuggestionResult,
) : ReplyGenerator {
    private val queue = ArrayDeque(results.toList().ifEmpty { listOf(success()) })
    val requests = mutableListOf<SuggestionRequest>()

    override suspend fun generate(request: SuggestionRequest): SuggestionResult {
        requests += request
        delay(GENERATE_MILLIS)
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }

    companion object {
        const val GENERATE_MILLIS = 1_000L
    }
}

class FakeInserter(
    var result: InsertResult = InsertResult.Inserted,
) : ReplyInserter {
    val inserted = mutableListOf<String>()

    override suspend fun insert(text: String): InsertResult {
        inserted += text
        return result
    }
}

class FakeFeedbackLog : FeedbackLog {
    data class Recorded(
        val chatKey: ChatKey,
        val model: String,
        val prompt: String,
        val suggestions: List<Suggestion>,
    )

    val recorded = mutableListOf<Recorded>()
    val marked = mutableListOf<Pair<Long, Int>>()
    var failRecord = false

    override suspend fun record(
        chatKey: ChatKey,
        model: String,
        prompt: String,
        suggestions: List<Suggestion>,
    ): Long {
        if (failRecord) throw IllegalStateException("db closed")
        recorded += Recorded(chatKey, model, prompt, suggestions)
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

    override suspend fun get(chatKey: ChatKey): String? = notes.value[chatKey]

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
