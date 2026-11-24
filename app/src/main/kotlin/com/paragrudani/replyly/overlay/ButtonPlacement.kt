package com.paragrudani.replyly.overlay

import com.paragrudani.replyly.screen.Bounds
import kotlin.math.abs

/** Top-left corner of the button window, in screen pixels. */
data class ButtonPosition(
    val x: Int,
    val y: Int,
)

/** The edge the button rests on. Old saved spots and apps never dragged read as [RIGHT]. */
enum class ButtonSide { LEFT, RIGHT }

/**
 * Where the button window sits ([position]) and how far the user may drag it: its top edge stays
 * within [minY]..[maxY] (below the status bar, above the keyboard) and its left edge within
 * [minX]..[maxX] (fully on screen). Always `minY <= maxY` and `minX <= leftX <= rightX <= maxX`.
 *
 * At rest the button sits on [side]: `position.x` is [leftX] or [rightX]. A slot built without
 * sides (tests, old callers) has both edges at `position.x` and no horizontal range.
 */
data class ButtonSlot(
    val position: ButtonPosition,
    val minY: Int,
    val maxY: Int,
    val side: ButtonSide = ButtonSide.RIGHT,
    val leftX: Int = position.x,
    val rightX: Int = position.x,
    val minX: Int = minOf(leftX, position.x),
    val maxX: Int = maxOf(rightX, position.x),
) {
    fun clampY(y: Int): Int = y.coerceIn(minY, maxY)

    fun clampX(x: Int): Int = x.coerceIn(minX, maxX)

    /** The window x of a button resting on [side]. */
    fun edgeX(side: ButtonSide): Int = if (side == ButtonSide.LEFT) leftX else rightX

    /**
     * The edge nearer a button window at [x]; an exact tie goes right. Unless the keyboard hangs
     * off the screen, this is "the button's centre left of the keyboard's centre".
     */
    fun snap(x: Int): ButtonSide =
        if (abs(x.toLong() - leftX) < abs(x.toLong() - rightX)) ButtonSide.LEFT else ButtonSide.RIGHT

    /** This slot with the button resting on [side], at the same height. */
    fun onSide(side: ButtonSide): ButtonSlot = copy(side = side, position = position.copy(x = edgeX(side)))
}

/**
 * Where the floating button goes (spec 7: above the keyboard). The keyboard window's bounds
 * include Gboard's toolbar, so anything above them is clear of it.
 *
 * - Right side of the keyboard (for a floating keyboard: of the floating rectangle) by default;
 *   the user may move it to the left side ([slot], [ButtonSide]).
 * - Just above the keyboard. If the focused input bar sits right on top of the keyboard (within
 *   [maxInputLiftPx]), above that bar instead, so the button never covers the chat app's send
 *   button, which lives on the right of the input bar.
 * - Always fully on screen, [marginPx] from every edge.
 *
 * The user can drag the button anywhere ([slot]); on release it snaps to the nearer left or right
 * edge ([ButtonSlot.snap]). The spot is remembered per app as (side, the gap between the button
 * and the keyboard top, "lift"), so it survives a taller or shorter keyboard and a rotation.
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
     * never dragged in this app, so the default height from [place] is used. [savedSide] is the
     * edge it rests on: right (the default spot's x) is the keyboard's right edge minus the margin,
     * left is the keyboard's left edge plus the margin, both kept fully on screen; left never lies
     * right of right (a keyboard narrower than two buttons puts both on one spot).
     *
     * When the input bar rests on the keyboard, the lowest spot ([ButtonSlot.maxY]) is the default
     * spot above that bar, and a saved spot is clamped to it on both sides: a drag never puts the
     * button over the send button (or the emoji and attach buttons on the left), also after a
     * multi-line input bar grew taller than when the spot was saved.
     *
     * Invariants (checked by `ButtonSlotPropertyTest`): `minY <= y <= maxY`; when the space allows
     * it, the button is fully below `topInsetPx + marginPx` and fully above `keyboard.top - marginPx`;
     * with the input bar on the keyboard, `maxY <= max(minY, default y)`; `slot(savedLiftPx =
     * liftOf(y, keyboard.top), savedSide = side)` gives back `y` on `side` for every `y` in range;
     * `x` is the edge of [savedSide]; both edges and the drag range stay fully on screen when the
     * screen is wide enough.
     */
    fun slot(
        keyboard: Bounds,
        input: Bounds?,
        screenWidth: Int,
        screenHeight: Int,
        topInsetPx: Int = 0,
        savedLiftPx: Int? = null,
        savedSide: ButtonSide = ButtonSide.RIGHT,
    ): ButtonSlot {
        val default = place(keyboard, input, screenWidth, screenHeight)
        val minY = topInsetPx.coerceAtLeast(0) + marginPx
        val aboveKeyboard = keyboard.top - marginPx - buttonSizePx
        val lowest = if (inputBarOnKeyboard(keyboard, input)) minOf(aboveKeyboard, default.y) else aboveKeyboard
        val maxY = maxOf(minY, lowest)
        val y = savedLiftPx?.let { keyboard.top - buttonSizePx - it } ?: default.y
        val minX = marginPx
        val maxX = maxOf(minX, screenWidth - marginPx - buttonSizePx)
        val rightX = default.x
        val leftX = (keyboard.left + marginPx).coerceIn(minX, maxX).coerceAtMost(rightX)
        return ButtonSlot(
            position = default.copy(y = y.coerceIn(minY, maxY)),
            minY = minY,
            maxY = maxY,
            leftX = leftX,
            rightX = rightX,
            minX = minX,
            maxX = maxX,
        ).onSide(savedSide)
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
