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
 * - the button: small, never focusable, so the chat's input keeps focus and the keyboard stays;
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
) : OverlayUi {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var button: View? = null
    private var buttonBusy = false
    private var panelWindow: PanelWindow? = null

    private class PanelWindow(
        val root: View,
        val compose: ComposeView,
        val owners: OverlayViewOwners,
        val sheet: PanelSheetState,
    )

    override fun showButton(position: ButtonPosition) {
        val existing = button
        if (existing != null) {
            val params = existing.layoutParams as WindowManager.LayoutParams
            params.x = position.x
            params.y = position.y
            windowManager.updateViewLayout(existing, params)
            return
        }
        val view = createOverlayButton(context, onButtonTap).apply { alpha = buttonAlpha() }
        val params =
            baseParams(buttonSizePx, buttonSizePx).apply {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                x = position.x
                y = position.y
            }
        windowManager.addView(view, params)
        button = view
    }

    override fun setButtonBusy(busy: Boolean) {
        buttonBusy = busy
        button?.alpha = buttonAlpha()
    }

    private fun buttonAlpha() = if (buttonBusy) BUSY_ALPHA else 1f

    override fun hideButton() {
        button?.let(::remove)
        button = null
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
        const val BUSY_ALPHA = 0.45f

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
