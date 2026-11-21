package com.paragrudani.replyly.invariants

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Source scan for the design-system rules in docs/DESIGN.md ("Enforced rules"). Raw colours,
 * animation specs, corner radii and haptics live only in `ui/theme/`; everything else uses the
 * tokens. If one fails, use the token or helper the message names; do not add an allow-list
 * entry without asking Parag.
 */
class DesignRulesTest {
    private val sources: List<File> =
        File("src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.contains("/replyly/ui/theme/") }
            .toList()

    /** `path:line: code` for every line outside `ui/theme/` that matches [pattern]. */
    private fun offenders(pattern: Regex): List<String> =
        sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (pattern.containsMatchIn(line)) "${file.invariantSeparatorsPath}:${i + 1}: ${line.trim()}" else null
            }
        }

    private fun check(
        pattern: Regex,
        fix: String,
    ) {
        assertWithMessage("Design rule broken (docs/DESIGN.md > Enforced rules). Fix: $fix\nOffending lines")
            .that(offenders(pattern))
            .isEmpty()
    }

    @Test
    fun `sources are found`() {
        assertWithMessage("scan found no sources; check the working dir").that(sources).isNotEmpty()
    }

    @Test
    fun `no hex colour literals`() {
        check(
            Regex("""\bColor\(\s*0x|"#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?\b"""),
            "use a MaterialTheme.colorScheme role or ReplylyTheme.colors (forIntent(...), accent, backdrop); " +
                "View code uses replylyColorScheme(context, dark, dynamic). New colours go in ui/theme/Color.kt.",
        )
    }

    @Test
    fun `no raw tween or spring`() {
        check(
            Regex(
                """(?<![.\w])(?:tween|spring)\s*(?:<[^>]*>)?\s*\(|\b(?:TweenSpec|SpringSpec)\s*\(|""" +
                    """import androidx\.compose\.animation\.core\.(?:tween|spring)\b""",
            ),
            "build specs via ReplylyTheme.motion (motion.tween(MotionDurations.X, MotionEasing.Y), motion.spring()) " +
                "so \"Remove animations\" is honoured; View code uses ReplylyMotion.forContext(context).",
        )
    }

    @Test
    fun `no RoundedCornerShape with dp literals`() {
        check(
            Regex("""RoundedCornerShape\([^)]*\d+(?:\.\d+)?f?\.dp"""),
            "use MaterialTheme.shapes.(extraSmall|small|medium|large|extraLarge), SheetShape, " +
                "or RoundedCornerShape(Radii.X) (XS 6, S 10, M 16, L 24, XL 28).",
        )
    }

    @Test
    fun `no raw performHapticFeedback`() {
        check(
            Regex("""\bperformHapticFeedback\s*\("""),
            "use rememberReplylyHaptics().perform(HapticEvent.X) in Compose or " +
                "view.performReplylyHaptic(HapticEvent.X) in View code (ui/theme/Haptics.kt).",
        )
    }
}
