package com.paragrudani.replyly.overlay

import com.paragrudani.replyly.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The bubble settings (#55) as a hot [StateFlow], like `ThemeSettings`: an overlay window reads
 * [look] synchronously, so it gets the right size and alpha in its first frame. Until the store
 * loads (or if it can't be read), the defaults apply: shown, 1.0x, 100 %, no shrink.
 */
class BubbleSettings(
    val look: StateFlow<BubbleLook>,
) {
    companion object {
        fun create(
            settings: SettingsRepository,
            scope: CoroutineScope,
        ): BubbleSettings =
            BubbleSettings(
                settings.settings
                    .map(BubbleLook::from)
                    .distinctUntilChanged()
                    // A broken settings file must not hide the button: defaults instead (see ThemeSettings).
                    .catch { emit(BubbleLook()) }
                    .stateIn(scope, SharingStarted.Eagerly, BubbleLook()),
            )
    }
}

/**
 * Applies every bubble look as it arrives, without waiting for an accessibility event (#55):
 * [onWindowSize] first (the placement must use the new window size), then the shown window's
 * look ([apply]), then [refresh] re-decides visibility and placement (`onEvent(WINDOWS)`), which
 * shows, hides or moves the button. A look change never reaches the tap path.
 */
fun CoroutineScope.followBubbleLook(
    looks: Flow<BubbleLook>,
    density: Float,
    onWindowSize: (px: Int) -> Unit,
    apply: (BubbleLook) -> Unit,
    refresh: () -> Unit,
): Job =
    launch {
        looks.collect { look ->
            onWindowSize(look.windowPx(density))
            apply(look)
            refresh()
        }
    }
