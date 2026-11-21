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
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.paragrudani.replyly.R
import com.paragrudani.replyly.panel.PanelRequest
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
        val view =
            ImageView(context).apply {
                setImageResource(R.drawable.overlay_button_icon)
                setBackgroundResource(R.drawable.overlay_button_background)
                scaleType = ImageView.ScaleType.CENTER
                elevation = 6 * resources.displayMetrics.density
                contentDescription = context.getString(R.string.overlay_button_description)
                setOnClickListener { onButtonTap() }
                alpha = buttonAlpha()
            }
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
        val compose =
            ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent { ReplylyTheme { PanelHost(panel, request, onPanelDismiss) } }
            }
        val root =
            BackKeyFrameLayout(context, onBack = onPanelDismiss).apply {
                addView(compose, FrameLayout.LayoutParams(MATCH, MATCH))
            }
        owners.attach(root)
        val params =
            baseParams(MATCH, MATCH).apply {
                @Suppress("DEPRECATION") // Still honoured for overlay windows; insets do the rest.
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
        windowManager.addView(root, params)
        panelWindow = PanelWindow(root, compose, owners)
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

/** A dimmed backdrop (tap to close) with the panel as a bottom sheet above the keyboard. */
@Composable
private fun PanelHost(
    panel: ReplyPanel,
    request: PanelRequest,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )
        Surface(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            panel.Content(request, onClose)
        }
    }
}
