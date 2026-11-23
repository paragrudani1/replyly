package com.paragrudani.replyly.llm

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.parser.x.XContext
import com.paragrudani.replyly.parser.x.XMode
import com.paragrudani.replyly.parser.x.XPost
import org.junit.Test

/** Hashtag and @-mention stripping on X (issue #46 §4). Handles are obviously fake. */
class XTagsTest {
    private val stripBoth = XTags.Keep(hashtags = false, mentions = false)

    private fun strip(
        text: String,
        keep: XTags.Keep = stripBoth,
    ) = XTags.strip(text, keep)

    @Test
    fun `a run at the start or the end is removed with its whitespace`() {
        assertThat(strip("@ravi_k @asha_dev totally agree with this")).isEqualTo("totally agree with this")
        assertThat(strip("Local-first wins. #Kotlin #buildinpublic")).isEqualTo("Local-first wins.")
        assertThat(strip("#dev Ship it today. @ravi_k")).isEqualTo("Ship it today.")
        assertThat(strip("Ship it\n\n#dev #kotlin")).isEqualTo("Ship it")
    }

    @Test
    fun `an inline one keeps its word and loses the sigil`() {
        assertThat(strip("love #Kotlin for this")).isEqualTo("love Kotlin for this")
        assertThat(strip("as @ravi_k said, caching wins")).isEqualTo("as ravi_k said, caching wins")
        assertThat(strip("Great point #Kotlin.")).isEqualTo("Great point Kotlin.")
        assertThat(strip("@ravi_k, you nailed it")).isEqualTo("ravi_k, you nailed it")
        assertThat(strip("full-width ＃タグ and ＠ravi_k too")).isEqualTo("full-width タグ and ravi_k too")
    }

    @Test
    fun `text that is not a hashtag or a mention is left alone`() {
        val untouched =
            listOf(
                "I write C# and F# at work",
                "we were #1 for a week",
                "mail me at asha@example.com today",
                "see https://example.com/@ravi_k and https://example.com/#top now",
                "keycap #️⃣ stays",
                "a lone # or @ stays",
                "html &#39; entity",
            )
        for (text in untouched) assertThat(strip(text)).isEqualTo(text)
    }

    @Test
    fun `each kind is kept on its own`() {
        val text = "@ravi_k agreed, #Kotlin is great #dev"
        assertThat(
            strip(text, XTags.Keep(hashtags = true, mentions = false)),
        ).isEqualTo("agreed, #Kotlin is great #dev")
        assertThat(
            strip(text, XTags.Keep(hashtags = false, mentions = true)),
        ).isEqualTo("@ravi_k agreed, Kotlin is great")
        assertThat(strip(text, XTags.Keep(hashtags = true, mentions = true))).isEqualTo(text)
    }

    @Test
    fun `only tags leaves nothing`() {
        assertThat(strip("#dev @ravi_k")).isEmpty()
    }

    @Test
    fun `stripping is idempotent even when a sigil follows another`() {
        val once = strip("so ##meta here")
        assertThat(strip(once)).isEqualTo(once)
    }

    @Test
    fun `keep follows my X voice and my own posts, per kind`() {
        val other = XPost("Meera Iyer", "meera_writes", "Hot take #local-first cc @someone", "2h")
        val x = XContext(XMode.REPLY, target = other)
        assertThat(XTags.keep("dry humour, no hashtags", x)).isEqualTo(XTags.Keep(hashtags = false, mentions = false))
        assertThat(XTags.keep("I tag #buildinpublic", x)).isEqualTo(XTags.Keep(hashtags = true, mentions = false))
        assertThat(XTags.keep("I thank @ravi_k a lot", x)).isEqualTo(XTags.Keep(hashtags = false, mentions = true))

        val mine = XPost("Asha", "asha_dev", "shipping again #buildinpublic", "1h", isMine = true)
        assertThat(XTags.keep("", XContext(XMode.REPLY, target = other, parents = listOf(mine))))
            .isEqualTo(XTags.Keep(hashtags = true, mentions = false))
        val quotedMine = other.copy(quoted = XPost("Asha", "asha_dev", "thanks @ravi_k", "1d", isMine = true))
        assertThat(XTags.keep("", XContext(XMode.QUOTE, target = quotedMine)))
            .isEqualTo(XTags.Keep(hashtags = false, mentions = true))
        // "C#" and an e-mail address in my voice are not habits.
        assertThat(
            XTags.keep("I write C#, mail asha@example.com", x),
        ).isEqualTo(XTags.Keep(hashtags = false, mentions = false))
    }
}
