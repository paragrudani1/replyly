package com.paragrudani.replyly.llm

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.history.HistoryRepository
import com.paragrudani.replyly.history.InMemoryContactNoteStore
import com.paragrudani.replyly.history.InMemoryHistoryRepository
import com.paragrudani.replyly.parser.ChatMessage
import com.paragrudani.replyly.parser.ParsedChat
import com.paragrudani.replyly.security.DatabaseKeyUnavailableException
import com.paragrudani.replyly.settings.InMemorySettingsRepository
import com.paragrudani.replyly.settings.ReplylySettings
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import java.time.Clock

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
    ) = ParsedChat(packageName, title, isGroup = false, messages = messages, historyApplies = historyApplies)

    @Test
    fun `merges the screen first, then reads the last N messages`() =
        runBlocking {
            history.merge(chat(messages = listOf(them("old 1"), me("old 2"))))

            val request = assembler.assemble(chat(messages = listOf(them("new 1"), them("new 2"))), instruction = null)

            assertThat(request.history.map { it.text }).containsExactly("old 2", "new 1", "new 2").inOrder()
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
            assertThat(request.history.map { it.text }).containsExactly("b", "c", "d").inOrder()
            assertThat(request.styleExamples).containsExactly("b", "d").inOrder()
            assertThat(history.lastMessages(generic.chatKey, 10)).isEmpty()
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
