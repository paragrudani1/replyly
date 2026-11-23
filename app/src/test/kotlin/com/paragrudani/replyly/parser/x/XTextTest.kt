package com.paragrudani.replyly.parser.x

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class XTextTest {
    @Test
    fun `handles normalise the @ and compare without case`() {
        assertThat(XHandles.normalize(" @Asha_Dev ")).isEqualTo("Asha_Dev")
        assertThat(XHandles.normalize("asha_dev")).isEqualTo("asha_dev")
        assertThat(XHandles.normalize("")).isEqualTo("")
        assertThat(XHandles.normalize("  ")).isEqualTo("")
        assertThat(XHandles.normalize(null)).isEqualTo("")
        assertThat(XHandles.normalize("asha dev")).isNull()
        assertThat(XHandles.normalize("a_handle_that_is_too_long")).isNull()
        assertThat(XHandles.normalize("x.com/asha")).isNull()
        assertThat(XHandles.same("@ASHA_DEV", "asha_dev")).isTrue()
        assertThat(XHandles.same("asha_dev", "asha_dev2")).isFalse()
        assertThat(XHandles.same(null, null)).isFalse()
        assertThat(XHandles.same("", "")).isFalse()
        assertThat(
            XHandles.findAll("Replying to @ravi_k and @asha_dev, mail a@b.com"),
        ).containsExactly("ravi_k", "asha_dev")
    }

    @Test
    fun `header lines`() {
        XText.header("Asha Dev @asha_dev · 2h")!!.let {
            assertThat(it.name).isEqualTo("Asha Dev")
            assertThat(it.handle).isEqualTo("asha_dev")
            assertThat(it.time).isEqualTo("2h")
        }
        XText.header("@asha_dev")!!.let {
            assertThat(it.name).isNull()
            assertThat(it.time).isNull()
        }
        assertThat(XText.header("@asha_dev · Nov 19")!!.time).isEqualTo("Nov 19")
        assertThat(XText.header("@ravi_k agreed")).isNull()
        assertThat(XText.header("Replying to @ravi_k")).isNull()
        assertThat(XText.header("Asha @asha_dev · tomorrow maybe")).isNull()
        assertThat(XText.header("Line one\nThanks @ravi_k")).isNull()
    }

    @Test
    fun `times, counts, replying-to lines and domains`() {
        for (t in listOf("now", "45s", "5m", "2h", "3d", "Nov 19", "21 Nov", "21 Nov 25", "Nov 19, 2025", "21/11/25")) {
            assertThat(XText.isPostTime(t)).isTrue()
        }
        for (t in listOf("2 hours", "tomorrow", "Asha", "12")) assertThat(XText.isPostTime(t)).isFalse()
        assertThat(XText.isAbsoluteTime("6:42 PM · 21 Nov 26 · 1,204 Views")).isTrue()
        assertThat(XText.isAbsoluteTime("18:42 · 21 Nov 26")).isTrue()
        assertThat(XText.isAbsoluteTime("6:42 PM")).isFalse()
        assertThat(XText.timePart("· 2h")).isEqualTo("2h")
        assertThat(XText.timePart("· hello")).isNull()
        for (c in listOf("3", "1.2K", "12,345", "48 Likes", "1,204 Views", "12 Reposts")) {
            assertThat(XText.isChrome(c)).isTrue()
        }
        for (c in listOf("3 days", "100 percent", "Offline first")) assertThat(XText.isChrome(c)).isFalse()
        assertThat(XText.replyingToHandles("Replying to @a_b, @c and 2 others")).containsExactly("a_b", "c").inOrder()
        assertThat(XText.replyingToHandles("Thanks @a_b")).isEmpty()
        assertThat(XText.domainOf("From example.com")).isEqualTo("example.com")
        assertThat(XText.domainOf("blog.example.org/post")).isEqualTo("blog.example.org")
        assertThat(XText.domainOf("1.2K")).isNull()
        assertThat(XText.domainOf("v1.2")).isNull()
        assertThat(XText.altText("Image")).isNull()
        assertThat(XText.altText("A cat on a laptop")).isEqualTo("A cat on a laptop")
        assertThat(XText.isGenericMedia("Photo.")).isTrue()
        assertThat(XText.isGenericMedia("A cat on a laptop")).isFalse()
        assertThat(XText.isFromDomain("From example.com")).isTrue()
        assertThat(XText.isFromDomain("example.com")).isFalse()
    }

    @Test
    fun `a replying-to line holds only handles`() {
        for (
        line in listOf(
            "Replying to @ravi_k",
            "Replying to @ravi_k and @asha_dev",
            "Replying to @a_b, @c and 2 others",
            "Replying to @a_b, @c, and 2 others",
            "Replying to @a_b @c",
            "replying to  @A_B",
        )
        ) {
            assertWithMessage(line).that(XText.isReplyingTo(line)).isTrue()
        }
        for (text in listOf("Replying to @ravi_k was a mistake", "Replying to everyone", "Thanks @ravi_k")) {
            assertWithMessage(text).that(XText.isReplyingTo(text)).isFalse()
        }
        assertThat(XText.replyingToHandles("Replying to @ravi_k was a mistake")).isEmpty()
    }
}
