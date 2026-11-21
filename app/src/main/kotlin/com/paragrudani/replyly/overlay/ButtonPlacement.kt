package com.paragrudani.replyly.overlay

import com.paragrudani.replyly.screen.Bounds

/** Top-left corner of the button window, in screen pixels. */
data class ButtonPosition(
    val x: Int,
    val y: Int,
)

/**
 * Where the button window sits ([position]) and how far the user may drag it: its top edge stays
 * within [minY]..[maxY] (below the status bar, above the keyboard). Always `minY <= maxY`.
 */
data class ButtonSlot(
    val position: ButtonPosition,
    val minY: Int,
    val maxY: Int,
) {
    fun clampY(y: Int): Int = y.coerceIn(minY, maxY)
}

/**
 * Where the floating button goes (spec 7: above the keyboard). The keyboard window's bounds
 * include Gboard's toolbar, so anything above them is clear of it.
 *
 * - Right side of the keyboard (for a floating keyboard: of the floating rectangle).
 * - Just above the keyboard. If the focused input bar sits right on top of the keyboard (within
 *   [maxInputLiftPx]), above that bar instead, so the button never covers the chat app's send
 *   button, which lives on the right of the input bar.
 * - Always fully on screen, [marginPx] from every edge.
 *
 * The user can drag the button up and down ([slot]); the spot is remembered per app as the gap
 * between the button and the keyboard top ("lift"), so it survives a taller or shorter keyboard.
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
        val anchorTop = input?.takeIf { inputBarOnKeyboard(keyboard, it) }?.top ?: keyboard.top
        val x = keyboard.right - marginPx - buttonSizePx
        val y = anchorTop - marginPx - buttonSizePx
        return ButtonPosition(
            x = x.coerceIn(marginPx, maxOf(marginPx, screenWidth - marginPx - buttonSizePx)),
            y = y.coerceIn(marginPx, maxOf(marginPx, screenHeight - marginPx - buttonSizePx)),
        )
    }

    /**
     * [place], plus the drag range and the user's remembered spot. [savedLiftPx] is the gap the
     * user left between the button's bottom edge and the keyboard top (see [liftOf]); `null` means
     * never dragged in this app, so the default spot from [place] is used.
     *
     * When the input bar rests on the keyboard, the lowest spot ([ButtonSlot.maxY]) is the default
     * spot above that bar, and a saved spot is clamped to it: a drag never puts the button over the
     * send button, also after a multi-line input bar grew taller than when the spot was saved.
     *
     * Invariants (checked by `ButtonSlotPropertyTest`): `minY <= y <= maxY`; when the space allows
     * it, the button is fully below `topInsetPx + marginPx` and fully above `keyboard.top - marginPx`;
     * with the input bar on the keyboard, `maxY <= max(minY, default y)`; `slot(savedLiftPx =
     * liftOf(y, keyboard.top))` gives back `y` for every `y` in range.
     */
    fun slot(
        keyboard: Bounds,
        input: Bounds?,
        screenWidth: Int,
        screenHeight: Int,
        topInsetPx: Int = 0,
        savedLiftPx: Int? = null,
    ): ButtonSlot {
        val default = place(keyboard, input, screenWidth, screenHeight)
        val minY = topInsetPx.coerceAtLeast(0) + marginPx
        val aboveKeyboard = keyboard.top - marginPx - buttonSizePx
        val lowest = if (inputBarOnKeyboard(keyboard, input)) minOf(aboveKeyboard, default.y) else aboveKeyboard
        val maxY = maxOf(minY, lowest)
        val y = savedLiftPx?.let { keyboard.top - buttonSizePx - it } ?: default.y
        return ButtonSlot(default.copy(y = y.coerceIn(minY, maxY)), minY, maxY)
    }

    /** The focused input bar sits right on top of the keyboard, so its send button is there too. */
    private fun inputBarOnKeyboard(
        keyboard: Bounds,
        input: Bounds?,
    ): Boolean =
        input != null &&
            input.bottom <= keyboard.top + marginPx &&
            keyboard.top - input.top in 0..maxInputLiftPx

    /** The gap between the bottom of a button at [y] and the keyboard top: what gets remembered. */
    fun liftOf(
        y: Int,
        keyboardTop: Int,
    ): Int = keyboardTop - buttonSizePx - y
}
