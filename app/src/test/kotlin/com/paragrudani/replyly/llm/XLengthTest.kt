package com.paragrudani.replyly.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Expected counts are what X's own composer shows (twitter-text v3 config), worked out by hand. */
class XLengthTest {
    @Test
    fun `ascii counts one per character`() {
        assertThat(XLength.weighted("")).isEqualTo(0)
        assertThat(XLength.weighted("Hello, world!")).isEqualTo(13)
        assertThat(XLength.weighted("a".repeat(280))).isEqualTo(280)
    }

    @Test
    fun `latin-1, accents and Devanagari are in the weight-1 ranges`() {
        assertThat(XLength.weighted("café")).isEqualTo(4)
        // न म स ् त े: six code points, all in U+0000-U+10FF.
        assertThat(XLength.weighted("नमस्ते")).isEqualTo(6)
        // General punctuation in the weight-1 ranges: en dash U+2013, curly quotes U+2018/U+201D.
        assertThat(XLength.weighted("a–b ‘c’ “d”")).isEqualTo(11)
    }

    @Test
    fun `CJK and other scripts outside the ranges count two`() {
        assertThat(XLength.weighted("日本語")).isEqualTo(6)
        assertThat(XLength.weighted("한국어")).isEqualTo(6)
        assertThat(XLength.weighted("ok 日本")).isEqualTo(7)
        // The ellipsis U+2026 is outside U+2018-U+201F and U+2032-U+2037.
        assertThat(XLength.weighted("…")).isEqualTo(2)
    }

    @Test
    fun `an emoji counts two, with skin tone, variation selector or keycap`() {
        assertThat(XLength.weighted("🔥")).isEqualTo(2)
        assertThat(XLength.weighted("👍🏽")).isEqualTo(2)
        assertThat(XLength.weighted("❤️")).isEqualTo(2)
        assertThat(XLength.weighted("1️⃣")).isEqualTo(2)
        assertThat(XLength.weighted("nice 🔥🔥")).isEqualTo(9)
    }

    @Test
    fun `a ZWJ sequence and a flag count as one emoji`() {
        // 👩‍💻: woman, ZWJ, laptop.
        assertThat(XLength.weighted("👩‍💻")).isEqualTo(2)
        // 👨‍👩‍👧‍👦: four people joined by three ZWJs.
        assertThat(XLength.weighted("👨‍👩‍👧‍👦")).isEqualTo(2)
        assertThat(XLength.weighted("🇮🇳")).isEqualTo(2)
        assertThat(XLength.weighted("🇮🇳🇯🇵")).isEqualTo(4)
    }

    @Test
    fun `a URL counts 23 with or without a scheme`() {
        assertThat(XLength.weighted("https://example.com/a/very/long/path?with=query&and=more")).isEqualTo(23)
        assertThat(XLength.weighted("http://x.co")).isEqualTo(23)
        assertThat(XLength.weighted("see example.com")).isEqualTo(4 + 23)
        assertThat(XLength.weighted("docs at developer.android.com/guide.")).isEqualTo(8 + 23 + 1)
        assertThat(XLength.weighted("(https://example.org)")).isEqualTo(1 + 23 + 1)
    }

    @Test
    fun `text that only looks like a domain is not a URL`() {
        // No known top-level domain, a number, a file name, an ellipsis between words.
        assertThat(XLength.weighted("end.of")).isEqualTo(6)
        assertThat(XLength.weighted("3.14")).isEqualTo(4)
        assertThat(XLength.weighted("wait...what")).isEqualTo(11)
        assertThat(XLength.weighted("a@example.com")).isEqualTo(13)
    }

    @Test
    fun `fits within the limit`() {
        assertThat(XLength.fits("a".repeat(280), 280)).isTrue()
        assertThat(XLength.fits("a".repeat(281), 280)).isFalse()
        assertThat(XLength.fits("日".repeat(140), 280)).isTrue()
        assertThat(XLength.fits("日".repeat(141), 280)).isFalse()
    }

    // --- trimming

    @Test
    fun `text within the limit is unchanged`() {
        val text = "Fair point about caching, but the cold start is the real cost here."
        assertThat(XLength.trim(text, 280)).isEqualTo(XLength.Trim(text, trimmed = false))
    }

    @Test
    fun `over the limit it cuts at the last sentence that fits`() {
        val text = "First sentence here. Second one is longer! Third pushes it over"
        // Limit 45: "First sentence here. Second one is longer!" is 42.
        assertThat(XLength.trim(text, 45)).isEqualTo(XLength.Trim("First sentence here. Second one is longer!", true))
        assertThat(XLength.trim(text, 30)).isEqualTo(XLength.Trim("First sentence here.", true))
    }

    @Test
    fun `without a sentence that fits it cuts at the last word that fits`() {
        val text = "one two three four five six seven"
        assertThat(XLength.trim(text, 15)).isEqualTo(XLength.Trim("one two three", true))
        assertThat(XLength.trim("abcdefghij klm", 12)).isEqualTo(XLength.Trim("abcdefghij", true))
    }

    @Test
    fun `a single word over the limit leaves nothing`() {
        assertThat(XLength.trim("a".repeat(300), 280)).isEqualTo(XLength.Trim("", true))
    }

    @Test
    fun `a decimal point or a domain dot is not a sentence end`() {
        val text = "Version 2.5 ships soon on example.com for everyone who asked"
        // Limit 30: no sentence end fits, so words: "Version 2.5 ships soon on" is 25.
        assertThat(XLength.trim(text, 30)).isEqualTo(XLength.Trim("Version 2.5 ships soon on", true))
    }

    @Test
    fun `counting uses the weights, so a URL or CJK text changes where it cuts`() {
        val text = "Read this: https://example.com/long/path/to/the/article and tell me"
        // "Read this: " 11 + URL 23 = 34; " and" makes 38.
        assertThat(XLength.trim(text, 38).text).isEqualTo("Read this: https://example.com/long/path/to/the/article and")
        assertThat(XLength.trim("日本語。韓国語。中国語", 16)).isEqualTo(XLength.Trim("日本語。韓国語。", true))
    }

    @Test
    fun `never splits an emoji sequence`() {
        val text = "so good 👨‍👩‍👧‍👦 truly"
        // "so good " is 8, the family emoji 2.
        assertThat(XLength.trim(text, 10).text).isEqualTo("so good 👨‍👩‍👧‍👦")
        assertThat(XLength.trim(text, 9).text).isEqualTo("so good")
    }
}
