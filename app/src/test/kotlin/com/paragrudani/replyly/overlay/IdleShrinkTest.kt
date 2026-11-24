package com.paragrudani.replyly.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** "Shrink when idle" on virtual time: a fake scheduler that runs due actions when time advances. */
class IdleShrinkTest {
    private var now = 0L
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private val changes = mutableListOf<Boolean>()

    private val idle =
        IdleShrink(
            schedule = { delay, action ->
                val timer = (now + delay) to action
                timers += timer
                ({ timers.remove(timer) })
            },
            onShrunkChange = { changes += it },
        )

    private fun advance(millis: Long) {
        val end = now + millis
        while (true) {
            val next = timers.filter { it.first <= end }.minByOrNull { it.first } ?: break
            timers.remove(next)
            now = next.first
            next.second()
        }
        now = end
    }

    private fun shownAndOn() {
        idle.setEnabled(true)
        idle.setShown(true)
    }

    @Test
    fun `shrinks 5 s after showing, not before`() {
        shownAndOn()

        advance(4_999)
        assertThat(idle.shrunk).isFalse()
        advance(1)
        assertThat(idle.shrunk).isTrue()
        assertThat(changes).containsExactly(true)
    }

    @Test
    fun `setting off never shrinks`() {
        idle.setShown(true)

        advance(60_000)

        assertThat(idle.shrunk).isFalse()
        assertThat(changes).isEmpty()
    }

    @Test
    fun `turning the setting off grows the dot back, on again waits a fresh 5 s`() {
        shownAndOn()
        advance(5_000)

        idle.setEnabled(false)
        assertThat(idle.shrunk).isFalse()
        idle.setEnabled(true)
        advance(4_999)
        assertThat(idle.shrunk).isFalse()
        advance(1)
        assertThat(idle.shrunk).isTrue()
    }

    @Test
    fun `a press grows the dot back and the release starts the 5 s again`() {
        shownAndOn()
        advance(5_000)

        idle.setTouching(true)
        assertThat(idle.shrunk).isFalse()
        advance(20_000)
        assertThat(idle.shrunk).isFalse()

        idle.setTouching(false)
        advance(4_999)
        assertThat(idle.shrunk).isFalse()
        advance(1)
        assertThat(idle.shrunk).isTrue()
        assertThat(changes).containsExactly(true, false, true).inOrder()
    }

    @Test
    fun `a touch before 5 s resets the timer`() {
        shownAndOn()
        advance(4_000)

        idle.setTouching(true)
        idle.setTouching(false)
        advance(4_000)
        assertThat(idle.shrunk).isFalse()
        advance(1_000)
        assertThat(idle.shrunk).isTrue()
    }

    @Test
    fun `a drag (press, then drag) keeps it full size until released`() {
        shownAndOn()
        advance(5_000)

        idle.setTouching(true) // press
        idle.setTouching(true) // drag start: still touching
        advance(10_000)
        assertThat(idle.shrunk).isFalse()
        idle.setTouching(false)
        advance(5_000)
        assertThat(idle.shrunk).isTrue()
    }

    @Test
    fun `never shrinks while busy, and grows back when a tap makes it busy`() {
        shownAndOn()
        idle.setBusy(true)
        advance(30_000)
        assertThat(idle.shrunk).isFalse()

        idle.setBusy(false)
        advance(5_000)
        assertThat(idle.shrunk).isTrue()
        idle.setBusy(true)
        assertThat(idle.shrunk).isFalse()
    }

    @Test
    fun `never shrinks with touch exploration (TalkBack) on`() {
        idle.setTouchExploration(true)
        shownAndOn()
        advance(30_000)
        assertThat(idle.shrunk).isFalse()

        idle.setTouchExploration(false)
        advance(5_000)
        assertThat(idle.shrunk).isTrue()
        idle.setTouchExploration(true)
        assertThat(idle.shrunk).isFalse()
    }

    @Test
    fun `each new show starts at full size, and hiding stops the timer`() {
        shownAndOn()
        advance(5_000)

        idle.setShown(false)
        assertThat(idle.shrunk).isFalse()
        assertThat(timers).isEmpty()
        idle.setShown(true)
        assertThat(idle.shrunk).isFalse()
        advance(5_000)
        assertThat(idle.shrunk).isTrue()
    }

    @Test
    fun `no timer is left behind once nothing can shrink`() {
        shownAndOn()
        idle.setEnabled(false)

        assertThat(timers).isEmpty()
    }
}
