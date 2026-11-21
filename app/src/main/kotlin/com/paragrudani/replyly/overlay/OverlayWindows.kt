package com.paragrudani.replyly.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.paragrudani.replyly.R
import com.paragrudani.replyly.panel.PanelRequest
import com.paragrudani.replyly.panel.PanelSheet
import com.paragrudani.replyly.panel.PanelSheetState
import com.paragrudani.replyly.panel.ReplyPanel
import com.paragrudani.replyly.ui.theme.ReplylyTheme

/**
 * The two overlay windows (TYPE_ACCESSIBILITY_OVERLAY, no extra permission needed):
 *
 * - the button: small, never focusable, so the chat's input keeps focus and the keyboard stays.
 *   It scales and fades in and out, and the user can drag it up and down within its [ButtonSlot];
 * - the panel: full screen and focusable, so its instruction field can take the keyboard. When it
 *   is removed, window focus returns to the chat app, whose input field never lost view focus.
 *
 * Both use screen coordinates (no inset fitting), the same space as `getBoundsInScreen`.
 * [context] is the accessibility service. Main thread only.
 */
class OverlayWindows(
    private val context: Context,
    private val panel: ReplyPanel,
    private val buttonSizePx: Int,
    private val onButtonTap: () -> Unit,
    private val onPanelDismiss: () -> Unit,
    /** The user dragged (or moved with TalkBack) the button to window top [y]. */
    private val onButtonMoved: (y: Int) -> Unit = {},
    /** How far one TalkBack "Move up/down" goes. */
    private val moveStepPx: Int = buttonSizePx,
) : OverlayUi {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var button: OverlayButtonView? = null
    private var slot: ButtonSlot? = null

    /** The button playing its hide animation; removed at its end or as soon as a new one shows. */
    private var leaving: OverlayButtonView? = null
    private var dragStartY = 0
    private var dragging = false
    private var buttonBusy = false
    private var panelWindow: PanelWindow? = null

    private class PanelWindow(
        val root: View,
        val compose: ComposeView,
        val owners: OverlayViewOwners,
        val sheet: PanelSheetState,
    )

    private val buttonListener =
        object : OverlayButtonView.Listener {
            override fun onTap() = onButtonTap()

            override fun onDragStart() {
                dragging = true
                dragStartY = buttonParams()?.y ?: return
            }

            override fun onDrag(dy: Int) {
                moveButtonTo(dragStartY + dy)
            }

            override fun onDragEnd() {
                dragging = false
                buttonParams()?.let { onButtonMoved(it.y) }
            }

            override fun onMoveStep(direction: Int) {
                val params = buttonParams() ?: return
                val y = moveButtonTo(params.y + direction * moveStepPx) ?: return
                onButtonMoved(y)
            }

            override fun canMoveStep(direction: Int): Boolean {
                val params = buttonParams() ?: return false
                val target = params.y + direction * moveStepPx
                return (slot?.clampY(target) ?: target) != params.y
            }
        }

    override fun showButton(slot: ButtonSlot) {
        this.slot = slot
        val existing = button
        if (existing != null) {
            val params = existing.layoutParams as WindowManager.LayoutParams
            params.x = slot.position.x
            // Never pull the button from under the finger; the drag clamps to the new range.
            if (!dragging) params.y = slot.position.y
            windowManager.updateViewLayout(existing, params)
            return
        }
        leaving?.let(::remove)
        leaving = null
        val view = OverlayButtonView(context, buttonListener).apply { setBusy(buttonBusy) }
        val params =
            baseParams(buttonSizePx, buttonSizePx).apply {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                x = slot.position.x
                y = slot.position.y
            }
        windowManager.addView(view, params)
        button = view
        view.animateIn()
    }

    override fun setButtonBusy(busy: Boolean) {
        buttonBusy = busy
        button?.setBusy(busy)
    }

    override fun hideButton(immediate: Boolean) {
        val view = button
        button = null
        slot = null
        dragging = false
        leaving?.let {
            it.animate().cancel()
            remove(it)
        }
        leaving = null
        if (view == null) return
        if (immediate) {
            view.animate().cancel()
            remove(view)
            return
        }
        // The fading window must not block touches meant for the chat app underneath.
        (view.layoutParams as? WindowManager.LayoutParams)?.let { params ->
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: IllegalArgumentException) {
                // Not attached any more; the fade below still ends in remove().
            }
        }
        leaving = view
        view.animateOut {
            if (leaving === view) leaving = null
            remove(view)
        }
    }

    /** The button window's view while shown (tests). */
    @VisibleForTesting
    internal val buttonView: OverlayButtonView? get() = button

    /** The button window playing its hide animation, if any (tests). */
    @VisibleForTesting
    internal val leavingView: OverlayButtonView? get() = leaving

    private fun buttonParams() = button?.layoutParams as? WindowManager.LayoutParams

    /** Moves the button window to [y] clamped to the slot; returns the clamped y (`null`: no button). */
    private fun moveButtonTo(y: Int): Int? {
        val view = button ?: return null
        val params = buttonParams() ?: return null
        val clamped = slot?.clampY(y) ?: y
        if (params.y != clamped) {
            params.y = clamped
            windowManager.updateViewLayout(view, params)
        }
        return clamped
    }

    override fun openPanel(request: PanelRequest) {
        closePanel()
        val owners = OverlayViewOwners()
        val sheet = PanelSheetState()
        lateinit var root: View
        // Every close slides the sheet down first; the window goes when the sheet says it is closed.
        // The fallback removes it anyway if no frame runs (the slide takes 250 ms).
        val requestClose = {
            if (!sheet.closeRequested) {
                sheet.requestClose()
                root.postDelayed({ if (panelWindow?.sheet === sheet) onPanelDismiss() }, CLOSE_FALLBACK_MS)
            }
        }
        val compose =
            ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    ReplylyTheme {
                        PanelSheet(sheet, onClosed = onPanelDismiss) { panel.Content(request, requestClose) }
                    }
                }
            }
        root =
            BackKeyFrameLayout(context, onBack = requestClose).apply {
                addView(compose, FrameLayout.LayoutParams(MATCH, MATCH))
            }
        owners.attach(root)
        val params =
            baseParams(MATCH, MATCH).apply {
                @Suppress("DEPRECATION") // Still honoured for overlay windows; insets do the rest.
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
        windowManager.addView(root, params)
        panelWindow = PanelWindow(root, compose, owners, sheet)
    }

    override fun closePanel() {
        val window = panelWindow ?: return
        panelWindow = null
        remove(window.root)
        window.compose.disposeComposition()
        window.owners.destroy()
    }

    override fun showNotice(notice: Notice) {
        val text =
            when (notice) {
                Notice.OFF_HERE -> R.string.overlay_notice_off_here
                Notice.NO_CHAT -> R.string.overlay_notice_no_chat
            }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("RtlHardcoded")
    private fun baseParams(
        width: Int,
        height: Int,
    ) = WindowManager
        .LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // LEFT, not START: x is a screen coordinate, also in right-to-left layouts.
            gravity = Gravity.TOP or Gravity.LEFT
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

    private fun remove(view: View) {
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: IllegalArgumentException) {
            // Already removed (the service is going away).
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        /** Longer than the sheet's slide-down (250 ms), short enough not to feel stuck. */
        const val CLOSE_FALLBACK_MS = 1_000L
    }
}

/** Back closes the panel. */
@SuppressLint("ViewConstructor")
private class BackKeyFrameLayout(
    context: Context,
    private val onBack: () -> Unit,
) : FrameLayout(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
