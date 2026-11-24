package com.paragrudani.replyly.invariants

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Source scan: tests never read the wall clock. A test that does passes only on some days
 * ("yesterday" in the X prompt held on 23 Nov 2026 and broke main the next day). Inject a fixed
 * clock instead: `testing.MutableClock.atLocal(...)` or `Clock.fixed(...)`, and pass it (and a
 * fixed `today`/zone) to every class or composable that would otherwise default to the system one.
 *
 * Robolectric's `android.os.SystemClock` is the paused looper's fake clock, so it is not banned.
 */
class NoWallClockInTestsTest {
    /** One allowed line: [file] ends with this path and the line contains [code]. */
    private data class Allowed(
        val file: String,
        val code: String,
        val reason: String,
    )

    private val allowed =
        listOf(
            Allowed(
                "history/HistoryMergePropertyTest.kt",
                "System.nanoTime()",
                "elapsed time for the printed run report only; never asserted on",
            ),
        )

    private val wallClock =
        Regex(
            """System\.currentTimeMillis|System\.nanoTime|Clock\.system|""" +
                """\b(Instant|LocalDate|LocalDateTime|LocalTime|ZonedDateTime|OffsetDateTime|OffsetTime|""" +
                """Year|YearMonth|MonthDay)\.now\(|""" +
                """\bDate\(\)|Calendar\.getInstance|GregorianCalendar\(\)""",
        )

    private val self = "/replyly/invariants/NoWallClockInTestsTest.kt"

    private val sources: List<File> =
        File("src/test/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.endsWith(self) }
            .toList()

    private fun isAllowed(
        file: File,
        line: String,
    ) = allowed.any { file.invariantSeparatorsPath.endsWith(it.file) && line.contains(it.code) }

    @Test
    fun `sources are found`() {
        assertWithMessage("scan found no test sources; check the working dir").that(sources).isNotEmpty()
    }

    @Test
    fun `tests never read the wall clock`() {
        val offenders =
            sources.flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    if (wallClock.containsMatchIn(line) && !isAllowed(file, line)) {
                        "${file.invariantSeparatorsPath}:${i + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
        assertWithMessage("Tests read the wall clock; inject testing.MutableClock or Clock.fixed instead")
            .that(offenders)
            .isEmpty()
    }

    @Test
    fun `every allowlist entry still matches a line`() {
        val stale =
            allowed.filterNot { entry ->
                sources.any { file ->
                    file.invariantSeparatorsPath.endsWith(entry.file) && file.readText().contains(entry.code)
                }
            }
        assertWithMessage("allowlist entries that match nothing; remove them").that(stale).isEmpty()
    }
}
