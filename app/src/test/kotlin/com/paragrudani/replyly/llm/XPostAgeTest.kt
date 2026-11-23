package com.paragrudani.replyly.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class XPostAgeTest {
    /** Sunday 22 Nov 2026, 11:08 in Kolkata. */
    private val now = ZonedDateTime.of(2026, 11, 22, 11, 8, 0, 0, ZoneId.of("Asia/Kolkata"))

    private fun age(shown: String) = XPostAge.describe(shown, now)

    @Test
    fun `relative times`() {
        assertThat(age("now")).isEqualTo("just now")
        assertThat(age("Now")).isEqualTo("just now")
        assertThat(age("45s")).isEqualTo("under a minute ago")
        assertThat(age("1m")).isEqualTo("1 minute ago")
        assertThat(age("12m")).isEqualTo("12 minutes ago")
        assertThat(age("1h")).isEqualTo("about 1 hour ago")
        assertThat(age("2h")).isEqualTo("about 2 hours ago")
        assertThat(age("23h")).isEqualTo("about 23 hours ago")
        assertThat(age("3d")).isEqualTo("3 days ago, Thu 19 Nov")
    }

    @Test
    fun `month and day, this year or the year before`() {
        assertThat(age("Nov 19")).isEqualTo("3 days ago, Thu 19 Nov")
        assertThat(age("19 Nov")).isEqualTo("3 days ago, Thu 19 Nov")
        assertThat(age("Nov 21")).isEqualTo("yesterday, Sat 21 Nov")
        assertThat(age("Nov 8")).isEqualTo("2 weeks ago, Sun 8 Nov")
        assertThat(age("Sep 2")).isEqualTo("about 2 months ago, Wed 2 Sep")
        // A day after today cannot be this year's: last December.
        assertThat(age("Dec 25")).isEqualTo("about 11 months ago, Thu 25 Dec 2025")
    }

    @Test
    fun `dates with a year`() {
        assertThat(age("Nov 19, 2025")).isEqualTo("over a year ago, Wed 19 Nov 2025")
        assertThat(age("19 Nov 2025")).isEqualTo("over a year ago, Wed 19 Nov 2025")
        assertThat(age("Mar 3, 2026")).isEqualTo("about 8 months ago, Tue 3 Mar")
    }

    @Test
    fun `the focal post's absolute time line`() {
        assertThat(age("8:05 AM · 22 Nov 26")).isEqualTo("about 3 hours ago, Sun 22 Nov 8:05 am")
        assertThat(age("6:42 PM · Nov 21, 2026")).isEqualTo("about 16 hours ago, Sat 21 Nov 6:42 pm")
        assertThat(age("18:42 · 21 Nov 26")).isEqualTo("about 16 hours ago, Sat 21 Nov 6:42 pm")
        assertThat(age("11:00 AM · 22 Nov 26")).isEqualTo("8 minutes ago, Sun 22 Nov 11:00 am")
        assertThat(age("6:42 PM · 18 Nov 26")).isEqualTo("4 days ago, Wed 18 Nov 6:42 pm")
        // With a view count after it, as X shows under the focal post.
        assertThat(age("6:42 PM · 21 Nov 26 · 1.2K Views")).isEqualTo("about 16 hours ago, Sat 21 Nov 6:42 pm")
    }

    @Test
    fun `unknown formats give nothing, so the prompt shows the time as shown`() {
        assertThat(age("")).isNull()
        assertThat(age("yesterday-ish")).isNull()
        assertThat(age("2 hrs")).isNull()
        assertThat(age("Nov 31")).isNull()
        assertThat(age("Foo 12")).isNull()
        assertThat(age("13:99 · 21 Nov 26")).isNull()
        assertThat(age("il y a 2 h")).isNull()
    }

    @Test
    fun `a time in the future is not an age`() {
        assertThat(age("9:00 PM · 22 Nov 26")).isNull()
        assertThat(age("Nov 22, 2027")).isNull()
    }
}
