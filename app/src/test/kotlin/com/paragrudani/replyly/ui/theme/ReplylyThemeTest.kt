package com.paragrudani.replyly.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.settings.InMemorySettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReplylyThemeTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `brand colours by default, wallpaper colours with dynamic colour on`() {
        var brand: ColorScheme? = null
        var dynamic: ColorScheme? = null
        compose.setContent {
            ReplylyTheme(darkTheme = false, dynamicColor = false) { brand = MaterialTheme.colorScheme }
            ReplylyTheme(darkTheme = false, dynamicColor = true) { dynamic = MaterialTheme.colorScheme }
        }
        compose.waitForIdle()
        assertThat(brand!!.primary).isEqualTo(ReplylyPalette.Ink)
        assertThat(dynamic!!.primary).isEqualTo(dynamicLightColorScheme(context).primary)
    }

    @Test
    fun `dark theme uses the dark brand scheme and type uses Figtree`() {
        var scheme: ColorScheme? = null
        var family: Any? = null
        compose.setContent {
            ReplylyTheme(darkTheme = true, dynamicColor = false) {
                scheme = MaterialTheme.colorScheme
                family = MaterialTheme.typography.bodyMedium.fontFamily
            }
        }
        compose.waitForIdle()
        assertThat(scheme!!.surface).isEqualTo(ReplylyPalette.Night)
        assertThat(family).isEqualTo(Figtree)
    }

    @Test
    fun `remove animations makes every spec snap and stagger zero`() {
        val resolver = context.contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertThat(isReducedMotion(context)).isTrue()
        val reduced = ReplylyMotion.forContext(context)
        assertThat(reduced.tween<Float>()).isInstanceOf(SnapSpec::class.java)
        assertThat(reduced.spring<Float>()).isInstanceOf(SnapSpec::class.java)
        assertThat(reduced.staggerDelay(2)).isEqualTo(0)
        assertThat(reduced.viewDuration(MotionDurations.MEDIUM)).isEqualTo(0L)

        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertThat(isReducedMotion(context)).isFalse()
        val normal = ReplylyMotion.forContext(context)
        assertThat(normal.tween<Float>(MotionDurations.MEDIUM)).isInstanceOf(TweenSpec::class.java)
        assertThat(normal.spring<Float>()).isInstanceOf(SpringSpec::class.java)
        assertThat(normal.staggerDelay(2)).isEqualTo(2 * MotionDurations.STAGGER)
        assertThat(normal.viewDuration(MotionDurations.MEDIUM)).isEqualTo(150L)
    }

    @Test
    fun `the theme follows the remove-animations setting while shown`() {
        val resolver = context.contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        var motion: ReplylyMotion? = null
        compose.setContent { ReplylyTheme(dynamicColor = false) { motion = ReplylyTheme.motion } }
        compose.waitForIdle()
        assertThat(motion!!.reduced).isFalse()

        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.waitForIdle()
        assertThat(motion!!.reduced).isTrue()
    }

    @Test
    fun `minTouchTarget makes a small tappable at least 48 dp`() {
        compose.setContent {
            Box(Modifier.testTag("small").clickable {}.minTouchTarget()) { Box(Modifier.size(20.dp)) }
        }
        compose.onNodeWithTag("small").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
    }

    @Test
    fun `intent colours map by name and fall back to neutral`() {
        val colors = LightExtendedColors
        assertThat(colors.forIntent("agree")).isEqualTo(colors.agree)
        assertThat(colors.forIntent(" Decline ")).isEqualTo(colors.decline)
        assertThat(colors.forIntent("ask")).isEqualTo(colors.ask)
        assertThat(colors.forIntent("joke")).isEqualTo(colors.otherIntent)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `theme settings follow the stored dynamic colour flag`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = InMemorySettingsRepository()
            val settings = ThemeSettings.create(repo, backgroundScope)
            assertThat(settings.dynamicColor.value).isFalse()
            repo.update { it.copy(useDynamicColor = true) }
            assertThat(settings.dynamicColor.first { it }).isTrue()
        }
}
