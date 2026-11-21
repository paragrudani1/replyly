package com.paragrudani.replyly.insert

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.screen.Bounds
import org.junit.Test

class InputFieldFinderTest {
    // Taken at tap time, keyboard open: the input bar sits high on the screen.
    private val ref =
        InputFieldRef(
            windowId = 7,
            packageName = "com.whatsapp",
            viewId = "com.whatsapp:id/entry",
            bounds = Bounds(30, 1300, 900, 1420),
        )

    @Test
    fun `finds the field by view id even after the keyboard closed and the bar moved down`() {
        val entry = FakeField(bounds = Bounds(30, 2200, 900, 2320))
        val search = FakeField(viewId = "com.whatsapp:id/search_src_text", bounds = Bounds(30, 1300, 900, 1420))
        val source = FakeFieldSource(mutableMapOf(7 to listOf(search, entry)))

        assertThat(InputFieldFinder.find(ref, source)).isSameInstanceAs(entry)
    }

    @Test
    fun `two fields with the same view id - takes the one nearest the remembered bounds`() {
        val far = FakeField(bounds = Bounds(30, 200, 900, 320))
        val near = FakeField(bounds = Bounds(30, 1310, 900, 1430))
        val source = FakeFieldSource(mutableMapOf(7 to listOf(far, near)))

        assertThat(InputFieldFinder.find(ref, source)).isSameInstanceAs(near)
    }

    @Test
    fun `no view id - the only editable field in the window`() {
        val only = FakeField(viewId = null, bounds = Bounds(0, 2200, 1080, 2320))
        val source = FakeFieldSource(mutableMapOf(7 to listOf(only)))

        assertThat(InputFieldFinder.find(ref.copy(viewId = null), source)).isSameInstanceAs(only)
    }

    @Test
    fun `no view id, several fields - prefers the focused one`() {
        val nearButUnfocused = FakeField(viewId = null, bounds = Bounds(30, 1300, 900, 1420))
        val focused = FakeField(viewId = null, bounds = Bounds(30, 2200, 900, 2320), focused = true)
        val source = FakeFieldSource(mutableMapOf(7 to listOf(nearButUnfocused, focused)))

        assertThat(InputFieldFinder.find(ref.copy(viewId = null), source)).isSameInstanceAs(focused)
    }

    @Test
    fun `no view id, several unfocused fields - the nearest to the remembered bounds`() {
        val top = FakeField(viewId = null, bounds = Bounds(30, 100, 900, 220))
        val bottom = FakeField(viewId = null, bounds = Bounds(30, 2200, 900, 2320))
        val source = FakeFieldSource(mutableMapOf(7 to listOf(top, bottom)))

        assertThat(InputFieldFinder.find(ref.copy(viewId = null), source)).isSameInstanceAs(bottom)
    }

    @Test
    fun `window gone - null, never a field in the app's other windows (that may be another chat)`() {
        val otherChatsEntry = FakeField()
        val source = FakeFieldSource(mutableMapOf(12 to listOf(otherChatsEntry)))

        assertThat(InputFieldFinder.find(ref, source)).isNull()
    }

    @Test
    fun `never picks a field of another app`() {
        val other = FakeField(packageName = "org.telegram.messenger")
        val source = FakeFieldSource(mutableMapOf(7 to listOf(other)))

        assertThat(InputFieldFinder.find(ref, source)).isNull()
    }

    @Test
    fun `never picks a password field`() {
        val secret = FakeField(password = true)
        val source = FakeFieldSource(mutableMapOf(7 to listOf(secret)))

        assertThat(InputFieldFinder.find(ref, source)).isNull()
    }

    @Test
    fun `field gone - null`() {
        assertThat(InputFieldFinder.find(ref, FakeFieldSource(mutableMapOf(7 to emptyList())))).isNull()
        assertThat(InputFieldFinder.find(ref, FakeFieldSource())).isNull()
    }
}
