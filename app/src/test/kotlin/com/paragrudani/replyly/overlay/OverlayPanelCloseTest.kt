package com.paragrudani.replyly.overlay

import android.os.Looper
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.panel.PanelRequest
import com.paragrudani.replyly.panel.ReplyPanel
import com.paragrudani.replyly.panel.whatsAppChat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * The panel window's 1 s close fallback: every close path (the panel's own insert, Close and Open
 * settings via `onClose`; Back; the sheet's backdrop and swipe via `PanelSheetState.requestClose`)
 * removes the window within the fallback even when no frame runs, and only once.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayPanelCloseTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private var panelClose: (() -> Unit)? = null
    private var dismissed = 0

    private val panel =
        object : ReplyPanel {
            @Composable
            override fun Content(
                request: PanelRequest,
                onClose: () -> Unit,
            ) {
                panelClose = onClose
            }
        }

    private lateinit var windows: OverlayWindows

    private fun open() {
        windows =
            OverlayWindows(context, panel, buttonSizePx = 104, onButtonTap = {}, onPanelDismiss = {
                dismissed++
                windows.closePanel()
            })
        windows.openPanel(PanelRequest(whatsAppChat))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        ShadowChoreographer.setPaused(false)
        if (::windows.isInitialized) windows.closePanel()
    }

    private fun assertFallbackClosesOnce() {
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofMillis(900))
        assertThat(dismissed).isEqualTo(0)
        assertThat(windows.panelRoot).isNotNull()

        looper.idleFor(Duration.ofMillis(200))
        assertThat(dismissed).isEqualTo(1)
        assertThat(windows.panelRoot).isNull()

        looper.idleFor(Duration.ofSeconds(2))
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `the panel's own close (insert, Close, Open settings) falls back after 1 s with no frames`() {
        open()
        ShadowChoreographer.setPaused(true)

        checkNotNull(panelClose).invoke()
        checkNotNull(panelClose).invoke()

        assertFallbackClosesOnce()
    }

    @Test
    fun `Back falls back after 1 s with no frames`() {
        open()
        ShadowChoreographer.setPaused(true)
        val root = checkNotNull(windows.panelRoot)

        root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))

        assertFallbackClosesOnce()
    }

    @Test
    fun `the backdrop and swipe path (requestClose on the sheet) falls back after 1 s with no frames`() {
        open()
        ShadowChoreographer.setPaused(true)

        // PanelSheet's backdrop tap and swipe-dismiss call exactly this (PanelSheetTest).
        checkNotNull(windows.panelSheet).requestClose()

        assertFallbackClosesOnce()
    }

    @Test
    fun `with frames the slide-down removes the window and the fallback does nothing more`() {
        open()

        checkNotNull(windows.panelSheet).requestClose()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertThat(dismissed).isEqualTo(1)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `a fallback from an earlier panel does not close the next one`() {
        open()
        ShadowChoreographer.setPaused(true)
        checkNotNull(windows.panelSheet).requestClose()

        windows.openPanel(PanelRequest(whatsAppChat))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))

        assertThat(dismissed).isEqualTo(0)
        assertThat(windows.panelRoot).isNotNull()
    }
}
