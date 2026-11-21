package com.paragrudani.replyly.overlay

import com.paragrudani.replyly.screen.Bounds

/** Top-left corner of the button window, in screen pixels. */
data class ButtonPosition(
    val x: Int,
    val y: Int,
)

/**
 * Where the floating button goes (spec 7: above the keyboard). The keyboard window's bounds
 * include Gboard's toolbar, so anything above them is clear of it.
 *
 * - Right side of the keyboard (for a floating keyboard: of the floating rectangle).
 * - Just above the keyboard. If the focused input bar sits right on top of the keyboard (within
 *   [maxInputLiftPx]), above that bar instead, so the button never covers the chat app's send
 *   button, which lives on the right of the input bar.
 * - Always fully on screen, [marginPx] from every edge.
 */
class ButtonPlacement(
    private val buttonSizePx: Int,
    private val marginPx: Int,
    private val maxInputLiftPx: Int,
) {
    fun place(
        keyboard: Bounds,
        input: Bounds?,
        screenWidth: Int,
        screenHeight: Int,
    ): ButtonPosition {
        val inputBarOnKeyboard =
            input != null &&
                input.bottom <= keyboard.top + marginPx &&
                keyboard.top - input.top in 0..maxInputLiftPx
        val anchorTop = if (inputBarOnKeyboard) input.top else keyboard.top
        val x = keyboard.right - marginPx - buttonSizePx
        val y = anchorTop - marginPx - buttonSizePx
        return ButtonPosition(
            x = x.coerceIn(marginPx, maxOf(marginPx, screenWidth - marginPx - buttonSizePx)),
            y = y.coerceIn(marginPx, maxOf(marginPx, screenHeight - marginPx - buttonSizePx)),
        )
    }
}
