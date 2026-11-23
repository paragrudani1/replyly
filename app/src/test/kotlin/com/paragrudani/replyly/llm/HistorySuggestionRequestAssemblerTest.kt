package com.paragrudani.replyly.llm

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.history.HistoryRepository
import com.paragrudani.replyly.history.InMemoryContactNoteStore
import com.paragrudani.replyly.history.InMemoryHistoryRepository
import com.paragrudani.replyly.history.StoredMessage
import com.paragrudani.replyly.parser.ChatKey
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.parser.ParsedChat
import com.paragrudani.replyly.parser.x.XParser
import com.paragrudani.replyly.security.DatabaseKeyUnavailableException
import com.paragrudani.replyly.settings.InMemorySettingsRepository
import com.paragrudani.replyly.settings.ReplylySettings
import com.paragrudani.replyly.testing.Fixtures
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class HistorySuggestionRequestAssemblerTest {
    private val history = InMemoryHistoryRepository(Clock.systemUTC())
    private val notes = InMemoryContactNoteStore()
    private val settings = InMemorySettingsRepository(ReplylySettings(historyMessageCount = 3, aboutMe = "I'm Parag"))
    private val assembler =
        HistorySuggestionRequestAssembler(history, notes, settings, appLabelOf = { "Label($it)" })

    private fun me(text: String) = ChatMessage(null, text, null, true)

    private fun them(text: String) = ChatMessage("Ravi", text, null, false)

    private fun chat(
        title: String = "Ravi",
        packageName: String = "com.whatsapp",
        messages: List<ChatMessage>,
        historyApplies: Boolean = true,
        isGroup: Boolean = false,
    ) = ParsedChat(packageName, title, isGroup = isGroup, messages = messages, historyApplies = historyApplies)

    @Test
    fun `merges the screen first, then reads the last N messages`() =
        runBlocking {
            history.merge(chat(messages = listOf(them("old 1"), me("old 2"))))

            val request = assembler.assemble(chat(messages = listOf(them("new 1"), them("new 2"))), instruction = null)

            assertThat(request.history.map { it.message.text }).containsExactly("old 2", "new 1", "new 2").inOrder()
        }

    @Test
    fun `fills app, chat, About me, contact note and instruction`() =
        runBlocking {
            val onScreen = chat(messages = listOf(them("hi")))
            notes.set(onScreen.chatKey, "Cricket friend")

            val request = assembler.assemble(onScreen, instruction = "  say I'll call at 6 ")

            assertThat(request.packageName).isEqualTo("com.whatsapp")
            assertThat(request.appLabel).isEqualTo("WhatsApp")
            assertThat(request.chatTitle).isEqualTo("Ravi")
            assertThat(request.aboutMe).isEqualTo("I'm Parag")
            assertThat(request.contactNote).isEqualTo("Cricket friend")
            assertThat(request.instruction).isEqualTo("say I'll call at 6")
        }

    // --- Dates and replies to me (prompt v2 on #26 PR 1's fields)

    private val kolkata = ZoneId.of("Asia/Kolkata")

    // Sunday 22 Nov 2026, 11:08 am in Kolkata.
    private val now = Instant.parse("2026-11-22T05:38:00Z")

    // 22:30 on Saturday 21 Nov in Kolkata.
    private val seenAt = Instant.parse("2026-11-21T17:00:00Z")

    private fun datedAssembler(
        stored: HistoryRepository = InMemoryHistoryRepository(Clock.fixed(seenAt, kolkata)),
        myNames: String = "",
    ) = HistorySuggestionRequestAssembler(
        stored,
        notes,
        InMemorySettingsRepository(ReplylySettings(myNames = myNames)),
        clock = Clock.fixed(now, ZoneOffset.UTC),
        zone = { kolkata },
        is24Hour = { true },
    )

    private fun conversation(request: SuggestionRequest): String =
        PromptBuilder
            .build(request)
            .user
            .substringAfter("<conversation>\n")
            .substringAfter("\n") // the Chat: line
            .substringBefore("\n</conversation>")

    private fun replyTo(request: SuggestionRequest): String =
        PromptBuilder
            .build(request)
            .user
            .substringAfter("<reply_to>\n")
            .substringBefore("\n</reply_to>")

    @Test
    fun `history messages carry their stored date, exact or approx, into the day headers`() =
        runBlocking {
            val screen =
                listOf(
                    // Under a "FRIDAY" separator: history stores the exact date.
                    ChatMessage("Ravi", "parso wala plan?", "9:40 pm", false, date = LocalDate.of(2026, 11, 20)),
                    // No separator above it: history stores the day it was first seen, approx.
                    ChatMessage("Ravi", "kal milte?", "10:20 pm", false),
                )

            val request = datedAssembler().assemble(chat(messages = screen), instruction = null)

            val (exact, approx) = request.history
            assertThat(exact.date).isEqualTo(LocalDate.of(2026, 11, 20))
            assertThat(exact.dateIsApprox).isFalse()
            assertThat(approx.date).isEqualTo(LocalDate.of(2026, 11, 21))
            assertThat(approx.dateIsApprox).isTrue()
            assertThat(request.history.map { it.firstSeenAt }).containsExactly(seenAt, seenAt)
            assertThat(conversation(request))
                .isEqualTo(
                    "— Friday · 20 Nov —\n" +
                        "[9:40 pm] Ravi: parso wala plan?\n" +
                        "— Yesterday · Sat 21 Nov (approx) —\n" +
                        "[10:20 pm] Ravi: kal milte?",
                )
            assertThat(request.now).isEqualTo(now.atZone(kolkata))
            assertThat(request.is24Hour).isTrue()
            assertThat(request.followUpAnyway).isFalse()
        }

    @Test
    fun `the stored date wins over the local day of firstSeenAt`() =
        runBlocking {
            // First seen at 01:30 on 22 Nov in Kolkata, but history stored 21 Nov (approx).
            val late = Instant.parse("2026-11-21T20:00:00Z")
            val stored =
                object : HistoryRepository by history {
                    override suspend fun merge(chat: ParsedChat): Int = 0

                    override suspend fun lastMessages(
                        chatKey: ChatKey,
                        limit: Int,
                    ) = listOf(
                        StoredMessage(chatKey, them("so gaye?"), late, LocalDate.of(2026, 11, 21), dateIsApprox = true),
                    )
                }

            val request = datedAssembler(stored).assemble(chat(messages = listOf(them("so gaye?"))), null)

            val message = request.history.single()
            assertThat(message.date).isEqualTo(LocalDate.of(2026, 11, 21))
            assertThat(message.dateIsApprox).isTrue()
            assertThat(message.firstSeenAt).isEqualTo(late)
            assertThat(conversation(request)).isEqualTo("— Yesterday · Sat 21 Nov (approx) —\nRavi: so gaye?")
        }

    @Test
    fun `on-screen-only messages keep the date their separator showed, exact, or none`() =
        runBlocking {
            val screen =
                listOf(
                    // Above the first separator on screen: no date.
                    ChatMessage("Ravi", "pehle wala", null, false),
                    ChatMessage("Ravi", "aaj 6 baje?", "10:50 am", false, date = LocalDate.of(2026, 11, 22)),
                )

            val request =
                datedAssembler().assemble(
                    chat(packageName = "com.slack", messages = screen, historyApplies = false),
                    instruction = null,
                )

            val (undated, dated) = request.history
            assertThat(undated.date).isNull()
            assertThat(undated.dateIsApprox).isFalse()
            assertThat(undated.firstSeenAt).isNull()
            assertThat(dated.date).isEqualTo(LocalDate.of(2026, 11, 22))
            assertThat(dated.dateIsApprox).isFalse()
            assertThat(dated.firstSeenAt).isNull()
            assertThat(conversation(request))
                .isEqualTo(
                    "— Date not shown —\n" +
                        "Ravi: pehle wala\n" +
                        "— Today · Sun 22 Nov —\n" +
                        "[10:50 am] Ravi: aaj 6 baje?",
                )
        }

    @Test
    fun `a reply to me is ranked first in a group, from history and from the screen`() =
        runBlocking {
            val screen =
                listOf(
                    ChatMessage(null, "kaun aa raha hai?", null, true),
                    ChatMessage("Amit", "main bhi", null, false),
                    ChatMessage("Neha", "haan pakka", null, false, repliesToMe = true),
                )
            val assembler = datedAssembler(myNames = " Parag, Paru ,, parag ")

            for (historyApplies in listOf(true, false)) {
                val packageName = if (historyApplies) "com.whatsapp" else "com.slack"
                val request =
                    assembler.assemble(
                        chat(
                            title = "College gang",
                            packageName = packageName,
                            messages = screen,
                            historyApplies = historyApplies,
                            isGroup = true,
                        ),
                        instruction = null,
                    )

                assertThat(request.history.map { it.repliesToMe }).containsExactly(false, false, true).inOrder()
                assertThat(request.myNames).containsExactly("Parag", "Paru").inOrder()
                assertThat(replyTo(request).lines())
                    .containsExactly("Neha (replies to me): haan pakka", "Amit: main bhi")
                    .inOrder()
            }
        }

    @Test
    fun `blank instruction becomes null`() =
        runBlocking {
            assertThat(assembler.assemble(chat(messages = listOf(them("hi"))), "   ").instruction).isNull()
        }

    @Test
    fun `style examples are my messages in this chat first, then from other chats of the same app`() =
        runBlocking {
            history.merge(chat(title = "Amit", messages = listOf(me("other chat msg"))))
            history.merge(chat(title = "Work", packageName = "org.telegram.messenger", messages = listOf(me("tg msg"))))
            history.merge(chat(messages = (1..30).map { me("mine $it") }))

            val request = assembler.assemble(chat(messages = listOf(them("so?"))), instruction = null)

            val styles = request.styleExamples
            // At most 10 from this chat, so other chats keep their 10-15 share.
            assertThat(styles.first()).isEqualTo("mine 21")
            assertThat(styles).contains("mine 30")
            assertThat(styles).doesNotContain("mine 20")
            assertThat(styles.last()).isEqualTo("other chat msg")
            assertThat(styles).doesNotContain("tg msg")
        }

    @Test
    fun `generic apps use only what is visible`() =
        runBlocking {
            val visible = listOf(them("a"), me("b"), them("c"), me("d"))
            val generic = chat(packageName = "com.slack", messages = visible, historyApplies = false)

            val request = assembler.assemble(generic, instruction = null)

            assertThat(request.appLabel).isEqualTo("Label(com.slack)")
            assertThat(request.history.map { it.message.text }).containsExactly("b", "c", "d").inOrder()
            assertThat(request.styleExamples).containsExactly("b", "d").inOrder()
            assertThat(history.lastMessages(generic.chatKey, 10)).isEmpty()
        }

    @Test
    fun `X stores nothing and builds the X prompt from the posts, with My X voice and the post length`() =
        runBlocking {
            settings.update { it.copy(myXVoice = "dry humour, no hashtags", xPostLength = 500) }
            val chat = XParser { "kiran_test" }.parse(Fixtures.snapshot("x/synthetic-reply_thread.json"))!!

            val request = assembler.assemble(chat, instruction = null)

            assertThat(request.appLabel).isEqualTo("X")
            assertThat(request.x).isEqualTo(chat.x)
            assertThat(request.myXVoice).isEqualTo("dry humour, no hashtags")
            assertThat(request.xPostLimit).isEqualTo(500)
            // ParsedChat.messages stays filled (the debug dump reads it); the X prompt does not use it.
            assertThat(request.history.map { it.message }).isEqualTo(chat.messages)
            assertThat(history.lastMessages(chat.chatKey, 10)).isEmpty()
            val prompt = PromptBuilder.build(request).user
            assertThat(prompt).contains("Mode: reply\nPost length: 500 (aim for at most 450)")
            assertThat(prompt).contains("My X voice:\ndry humour, no hashtags")
            assertThat(prompt).doesNotContain("<conversation>")
            assertThat(prompt.substringAfter("<thread>").substringBefore("</thread>"))
                .contains("Ravi K (@ravi_k) · 5h (about 5 hours ago):\n  Shipping the offline mode")
            assertThat(prompt.substringAfter("<target>").substringBefore("</target>"))
                .contains("Meera Iyer (@meera_writes) · 6:42 PM · 21 Nov 26")
        }

    @Test
    fun `a chat request carries no X fields`() =
        runBlocking {
            settings.update { it.copy(myXVoice = "dry humour") }
            val request = assembler.assemble(chat(messages = listOf(them("hi"))), instruction = null)
            assertThat(request.x).isNull()
            assertThat(request.myXVoice).isEmpty()
        }

    @Test
    fun `an unavailable database key becomes the StorageLocked failure`() =
        runBlocking {
            val locked =
                object : HistoryRepository by history {
                    override suspend fun merge(chat: ParsedChat): Int =
                        throw IllegalStateException(
                            "open failed",
                            DatabaseKeyUnavailableException(IOException("keystore")),
                        )
                }
            val lockedAssembler = HistorySuggestionRequestAssembler(locked, notes, settings)

            val thrown = runCatching { lockedAssembler.assemble(chat(messages = listOf(them("hi"))), null) }

            val failure = (thrown.exceptionOrNull() as SuggestionFailureException).failure
            assertThat(failure).isEqualTo(SuggestionResult.Failure.StorageLocked)
        }

    @Test
    fun `other storage errors are rethrown unchanged`() =
        runBlocking {
            val broken =
                object : HistoryRepository by history {
                    override suspend fun merge(chat: ParsedChat): Int = throw IOException("disk full")
                }

            val thrown =
                runCatching {
                    HistorySuggestionRequestAssembler(
                        broken,
                        notes,
                        settings,
                    ).assemble(chat(messages = listOf(them("hi"))), null)
                }

            assertThat(thrown.exceptionOrNull()).isInstanceOf(IOException::class.java)
        }
}
