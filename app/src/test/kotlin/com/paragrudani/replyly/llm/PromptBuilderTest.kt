package com.paragrudani.replyly.llm

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.parser.ChatMessage
import org.junit.Test

class PromptBuilderTest {
    private fun me(
        text: String,
        time: String? = null,
    ) = ChatMessage(sender = null, text = text, timeShown = time, isMe = true)

    private fun them(
        text: String,
        sender: String = "Ravi",
        time: String? = null,
    ) = ChatMessage(sender = sender, text = text, timeShown = time, isMe = false)

    private fun request(
        history: List<ChatMessage> = listOf(them("Hi"), me("Hey")),
        isGroup: Boolean = false,
        aboutMe: String = "",
        contactNote: String? = null,
        styleExamples: List<String> = emptyList(),
        instruction: String? = null,
    ) = SuggestionRequest(
        packageName = "com.whatsapp",
        appLabel = "WhatsApp",
        chatTitle = if (isGroup) "College gang" else "Ravi",
        isGroup = isGroup,
        history = history,
        aboutMe = aboutMe,
        contactNote = contactNote,
        styleExamples = styleExamples,
        instruction = instruction,
    )

    private fun user(
        request: SuggestionRequest,
        limits: PromptLimits = PromptLimits(),
    ) = PromptBuilder.build(request, limits = limits).user

    @Test
    fun `names the app and the chat`() {
        assertThat(user(request())).contains("App: WhatsApp")
        assertThat(user(request())).contains("Chat: Ravi (1:1)")
        assertThat(user(request(isGroup = true))).contains("Chat: College gang (group)")
    }

    @Test
    fun `labels history me and them, oldest first, with time shown`() {
        val history = listOf(them("Kal movie chalein?", time = "6:40 pm"), me("Haan pakka", time = "6:42 pm"))
        val text = user(request(history = history))
        assertThat(text).contains("[6:40 pm] them: Kal movie chalein?\n[6:42 pm] me: Haan pakka")
    }

    @Test
    fun `group history names the sender`() {
        val history = listOf(them("Kab mil rahe ho?", sender = "Priya"), them("Sunday?", sender = "Amit"), me("Done"))
        val text = user(request(history = history, isGroup = true))
        assertThat(text).contains("them (Priya): Kab mil rahe ho?\nthem (Amit): Sunday?\nme: Done")
    }

    @Test
    fun `Hinglish history is kept verbatim in Latin script`() {
        val history = listOf(them("Bhai kal aa raha hai na? 😅"), me("Haa yaar, pakka aaunga"))
        val text = user(request(history = history))
        assertThat(text).contains("them: Bhai kal aa raha hai na? 😅")
        assertThat(text).contains("me: Haa yaar, pakka aaunga")
    }

    @Test
    fun `multi-line messages stay inside their own entry`() {
        val text = user(request(history = listOf(them("line one\nline two"), me("ok"))))
        assertThat(text).contains("them: line one\n  line two\nme: ok")
    }

    @Test
    fun `includes About me, contact note and instruction when present`() {
        val text =
            user(
                request(
                    aboutMe = "Parag, product engineer in Ahmedabad",
                    contactNote = "College friend, we play cricket on Sundays",
                    instruction = "say I'll call at 6",
                ),
            )
        assertThat(text).contains("About me:\nParag, product engineer in Ahmedabad")
        assertThat(text).contains("Note about this chat:\nCollege friend, we play cricket on Sundays")
        assertThat(text).contains("My instruction for this reply: say I'll call at 6")
    }

    @Test
    fun `leaves out empty optional sections`() {
        val text = user(request(aboutMe = "  ", contactNote = "", instruction = " "))
        assertThat(text).doesNotContain("About me")
        assertThat(text).doesNotContain("Note about this chat")
        assertThat(text).doesNotContain("My instruction")
        assertThat(text).doesNotContain("How I write")
    }

    @Test
    fun `style examples are deduplicated, skip what history already shows, and are capped`() {
        val history = listOf(them("Coming?"), me("Haan 5 min"))
        val styles = listOf("Haan 5 min", "ok", "OK ", "Chal theek hai 👍", "", "Pakka?", "ok")
        val text = user(request(history = history, styleExamples = styles), PromptLimits(maxStyleExamples = 2))
        val section = text.substringAfter("How I write").substringBefore("\n\n")
        assertThat(section).contains("- ok\n- Chal theek hai 👍")
        assertThat(section).doesNotContain("Haan 5 min")
        assertThat(section).doesNotContain("Pakka?")
    }

    @Test
    fun `truncates long messages and style examples`() {
        val long = "a".repeat(50)
        val text =
            user(
                request(history = listOf(them(long)), styleExamples = listOf("b".repeat(50))),
                PromptLimits(maxMessageChars = 10, maxStyleExampleChars = 5),
            )
        assertThat(text).contains("them: aaaaaaaaa…\n")
        assertThat(text).contains("- bbbb…")
        assertThat(text).doesNotContain("a".repeat(11))
    }

    @Test
    fun `drops the oldest history when it exceeds the budget`() {
        val history = (1..10).map { them("message number $it") }
        val text = user(request(history = history), PromptLimits(maxHistoryChars = 80))
        assertThat(text).contains("message number 10")
        assertThat(text).contains("message number 9")
        assertThat(text).doesNotContain("message number 1\n")
        assertThat(text).doesNotContain("message number 5")
    }

    @Test
    fun `caps About me, note and instruction`() {
        val text =
            user(
                request(aboutMe = "x".repeat(40), contactNote = "y".repeat(40), instruction = "z".repeat(40)),
                PromptLimits(maxAboutMeChars = 10, maxNoteChars = 10, maxInstructionChars = 10),
            )
        assertThat(text).doesNotContain("x".repeat(10))
        assertThat(text).doesNotContain("y".repeat(10))
        assertThat(text).doesNotContain("z".repeat(10))
    }

    @Test
    fun `empty history says so`() {
        assertThat(user(request(history = emptyList()))).contains("(no messages visible)")
    }

    @Test
    fun `default task asks for 3 suggestions with different intents`() {
        val prompt = PromptBuilder.build(request())
        assertThat(prompt.user).contains("3 suggestions")
        assertThat(prompt.system).contains("""{"suggestions":[{"intent":"...","text":"..."}]}""")
    }

    @Test
    fun `single-intent task asks for exactly 1 suggestion with that intent`() {
        val prompt = PromptBuilder.build(request(), PromptTask.Single(FixedIntent.DECLINE))
        assertThat(prompt.user).contains("exactly 1 suggestion")
        assertThat(prompt.user).contains("\"decline\"")
        assertThat(prompt.user).doesNotContain("3 suggestions")
        assertThat(prompt.system).isEqualTo(PromptBuilder.build(request()).system)
    }

    @Test
    fun `system prompt carries the language, style, honesty and output rules`() {
        val system = PromptBuilder.build(request()).system
        assertThat(system).contains("same language and the same script")
        assertThat(system).contains("Latin letters")
        assertThat(system).contains("emoji")
        assertThat(system).contains("Never invent")
        assertThat(system).contains("only the JSON")
    }

    @Test
    fun `fullText joins system and user for the feedback log`() {
        val prompt = PromptBuilder.build(request())
        assertThat(prompt.fullText).isEqualTo(prompt.system + "\n\n" + prompt.user)
    }
}
