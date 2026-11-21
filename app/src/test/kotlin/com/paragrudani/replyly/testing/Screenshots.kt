package com.paragrudani.replyly.testing

import android.content.res.Configuration
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.ThresholdValidator
import com.github.takahirom.roborazzi.captureRoboImage
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import com.paragrudani.replyly.ui.theme.Spacing
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * One rendering of a screenshot. Every shot is taken with the brand colours (dynamic colour off)
 * and with reduced motion on, so animations sit at their end state and loops never start.
 */
enum class ShotVariant(
    val suffix: String,
    val dark: Boolean,
    val fontScale: Float,
) {
    Light("light", dark = false, fontScale = 1f),
    Dark("dark", dark = true, fontScale = 1f),

    /** Light at the system font size 130 %: catches clipped and overlapping text. */
    LargeFont("light_font130", dark = false, fontScale = 1.3f),
    ;

    companion object {
        val ALL = entries.toList()
        val LIGHT_DARK = listOf(Light, Dark)
    }
}

/**
 * Screenshot tests in a few lines (docs/SCREENSHOTS.md). The test class needs the Robolectric
 * runner plus [ScreenshotTest] settings:
 *
 * ```
 * @RunWith(RobolectricTestRunner::class)
 * @GraphicsMode(GraphicsMode.Mode.NATIVE)
 * @Config(qualifiers = ScreenshotTest.DEVICE)
 * class MyScreenScreenshotTest {
 *     @get:Rule val shots = ScreenshotRule()
 *
 *     @Test fun main() = shots.screen("my_screen/main") { MyScreen(state) }
 *     @Test fun chip() = shots.component("my_screen/chip") { MyChip() }
 * }
 * ```
 *
 * Each call writes or verifies `app/src/test/screenshots/<name>_<variant>.png`, one per [ShotVariant].
 */
class ScreenshotRule : TestRule {
    val compose: ComposeContentTestRule = createComposeRule()

    override fun apply(
        base: Statement,
        description: Description,
    ): Statement = compose.apply(base, description)

    /** A whole screen at the S23's size (360 x 780 dp), on the theme background. */
    fun screen(
        name: String,
        variants: List<ShotVariant> = ShotVariant.ALL,
        content: @Composable () -> Unit,
    ) = capture(name, variants, fullScreen = true, width = null, content = content)

    /**
     * A component on the theme background with 16 dp around it, [width] wide (default: the
     * screen width minus the padding), as tall as it wants.
     */
    fun component(
        name: String,
        variants: List<ShotVariant> = ShotVariant.ALL,
        width: Dp? = null,
        content: @Composable () -> Unit,
    ) = capture(name, variants, fullScreen = false, width = width, content = content)

    private fun capture(
        name: String,
        variants: List<ShotVariant>,
        fullScreen: Boolean,
        width: Dp?,
        content: @Composable () -> Unit,
    ) {
        require(variants.isNotEmpty())
        var current by mutableStateOf(variants.first())
        compose.setContent {
            ShotFrame(current) {
                // A Surface, not a background: it also sets the content colour (onBackground).
                Surface(Modifier.testTag(SHOT_TAG).then(if (fullScreen) Modifier.fillMaxSize() else Modifier)) {
                    if (fullScreen) {
                        content()
                    } else {
                        Box(Modifier.padding(Spacing.L)) {
                            Box(if (width != null) Modifier.width(width) else Modifier) { content() }
                        }
                    }
                }
            }
        }
        for (variant in variants) {
            current = variant
            compose.waitForIdle()
            compose.onNodeWithTag(SHOT_TAG).captureRoboImage(fileName(name, variant), ScreenshotTest.options)
        }
    }

    private companion object {
        const val SHOT_TAG = "screenshot_root"
    }
}

/** Settings shared by every screenshot test. */
object ScreenshotTest {
    /**
     * Galaxy S23 in dp (1080 x 2340 px at 3x is 360 x 780 dp), rendered at 2x so the PNGs stay
     * small, in US English so number and date formats are fixed. Use as
     * `@Config(qualifiers = ScreenshotTest.DEVICE)`, together with `@GraphicsMode(NATIVE)`.
     */
    const val DEVICE = "en-rUS-w360dp-h780dp-xhdpi"

    /**
     * Compare settings (docs/SCREENSHOTS.md, "Stability"): a pixel counts as changed when its colour
     * distance is above 0.007 (about 2 of 255 per channel: rounding noise stays below it), and a shot
     * fails on any changed pixel. A threshold of 0.1 % of pixels let a one-letter change pass, so
     * there is none.
     */
    val options =
        RoborazziOptions(
            compareOptions =
                RoborazziOptions.CompareOptions(
                    imageComparator = SimpleImageComparator(maxDistance = 0.007f),
                    resultValidator = ThresholdValidator(0f),
                ),
        )
}

/** Shot of a classic View (the overlay button); build the View in the colours of [variant]. */
fun View.captureShot(
    name: String,
    variant: ShotVariant,
) = captureRoboImage(fileName(name, variant), ScreenshotTest.options)

fun fileName(
    name: String,
    variant: ShotVariant,
): String = "${name}_${variant.suffix}.png"

/** Forces dark mode and the font scale for everything inside, including a nested ReplylyTheme(). */
@Composable
private fun ShotFrame(
    variant: ShotVariant,
    content: @Composable () -> Unit,
) {
    val base = LocalConfiguration.current
    val configuration =
        Configuration(base).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (variant.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            fontScale = variant.fontScale
        }
    val density = LocalDensity.current.density
    CompositionLocalProvider(
        LocalConfiguration provides configuration,
        LocalDensity provides Density(density, variant.fontScale),
    ) {
        ReplylyTheme(darkTheme = variant.dark, dynamicColor = false, reducedMotion = true, content = content)
    }
}
