package com.paragrudani.replyly.invariants

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Cheap source scan for the hard rules in AGENTS.md. If one fails, do not weaken it: change the
 * code. Ask Parag before editing this list.
 */
class InvariantsTest {
    private val mainSources: List<File> =
        File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun offenders(
        pattern: Regex,
        allowedIn: (File) -> Boolean = { false },
    ): List<String> =
        mainSources
            .filterNot(allowedIn)
            .filter { pattern.containsMatchIn(it.readText()) }
            .map { it.invariantSeparatorsPath }

    @Test
    fun `sources are found`() {
        assertWithMessage("scan found no sources; check the working dir").that(mainSources).isNotEmpty()
    }

    @Test
    fun `never acts on another app except setting text`() {
        // Clicking, pressing Enter, gestures or global actions could send a message.
        val forbidden =
            Regex("""ACTION_CLICK|ACTION_LONG_CLICK|ACTION_IME_ENTER|performGlobalAction|dispatchGesture""")
        assertWithMessage("Replyly must never send or click in other apps").that(offenders(forbidden)).isEmpty()
    }

    @Test
    fun `performAction only in the input field source`() {
        // A raw id (performAction(16) is a click) would slip past the name scan above.
        val action = Regex("""\bperformAction\(""")
        assertWithMessage("performAction outside insert/AccessibilityInputFieldSource.kt")
            .that(
                offenders(action) {
                    it.invariantSeparatorsPath.endsWith("/replyly/insert/AccessibilityInputFieldSource.kt")
                },
            ).isEmpty()
    }

    @Test
    fun `network only in the llm package`() {
        val network = Regex("""import okhttp3\.|java\.net\.URL\b|HttpURLConnection""")
        assertWithMessage("network code outside llm/")
            .that(offenders(network) { it.invariantSeparatorsPath.contains("/replyly/llm/") })
            .isEmpty()
    }
}
