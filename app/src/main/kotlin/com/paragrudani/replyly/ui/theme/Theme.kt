package com.paragrudani.replyly.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import com.paragrudani.replyly.ReplylyApp
import com.paragrudani.replyly.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The Replyly theme: brand colours (or Material You when the user turned it on), Figtree type,
 * shapes, extended colours and motion. Wrap every surface in it, including windows outside an
 * Activity (the overlay button and panel); the defaults read the system and the setting.
 *
 * Tests and previews pin the arguments (`darkTheme`, `dynamicColor = false`, `reducedMotion`).
 */
@Composable
fun ReplylyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = rememberDynamicColorSetting(),
    reducedMotion: Boolean = rememberReducedMotion(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme =
        remember(context, darkTheme, dynamicColor) { replylyColorScheme(context, darkTheme, dynamicColor) }
    val extended = remember(scheme, darkTheme, dynamicColor) { replylyExtendedColors(scheme, darkTheme, dynamicColor) }
    val motion = remember(reducedMotion) { ReplylyMotion(reducedMotion) }
    CompositionLocalProvider(
        LocalReplylyColors provides extended,
        LocalReplylyMotion provides motion,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = ReplylyTypography,
            shapes = ReplylyShapes,
            content = content,
        )
    }
}

/** Replyly's additions to `MaterialTheme`. Spacing, radii and elevation are plain objects: [Spacing], [Radii], [Elevation]. */
object ReplylyTheme {
    /** Intent chip colours, the accent and the backdrop. */
    val colors: ReplylyColors
        @Composable @ReadOnlyComposable
        get() = LocalReplylyColors.current

    /** Animation specs that respect "Remove animations". */
    val motion: ReplylyMotion
        @Composable @ReadOnlyComposable
        get() = LocalReplylyMotion.current
}

internal val LocalReplylyColors = staticCompositionLocalOf { LightExtendedColors }
internal val LocalReplylyMotion = staticCompositionLocalOf { ReplylyMotion(reduced = false) }

/**
 * The user's "Material You colours" setting. False (brand colours) in previews, outside the app
 * process, and until the setting has loaded.
 */
@Composable
fun rememberDynamicColorSetting(): Boolean {
    if (LocalInspectionMode.current) return false
    val settings = LocalContext.current.themeSettings() ?: return false
    return settings.dynamicColor.collectAsState().value
}

/** The current dynamic-colour setting, for View code outside Compose (false until loaded). */
fun Context.dynamicColorSetting(): Boolean = themeSettings()?.dynamicColor?.value ?: false

private fun Context.themeSettings(): ThemeSettings? = (applicationContext as? ReplylyApp)?.graph?.themeSettings

/**
 * Theme preferences as hot state, so a window opened from the accessibility service (no
 * Activity, no time to wait) gets the right colours in its first frame.
 */
class ThemeSettings(
    val dynamicColor: StateFlow<Boolean>,
) {
    companion object {
        fun create(
            settings: SettingsRepository,
            scope: CoroutineScope,
        ): ThemeSettings =
            ThemeSettings(
                settings.settings
                    .map { it.useDynamicColor }
                    .distinctUntilChanged()
                    // A broken settings file must not break every screen: brand colours instead.
                    // Note: `catch` completes the flow after emitting, so after one read error the
                    // value stays `false` (brand colours) until the app restarts. Acceptable for a
                    // cosmetic setting; add a retry here if it ever matters.
                    .catch { emit(false) }
                    .stateIn(scope, SharingStarted.Eagerly, false),
            )
    }
}
