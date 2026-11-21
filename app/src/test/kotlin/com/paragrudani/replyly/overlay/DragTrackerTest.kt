package com.paragrudani.replyly.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Slop 24 px, like an S23 (8 dp at 3x). */
class DragTrackerTest {
    private val tracker = DragTracker(slopPx = 24f)

    @Test
    fun `down then up in place is a tap`() {
        assertThat(tracker.down(500f, 1200f)).isEqualTo(DragStep.Press)
        assertThat(tracker.up(500f, 1200f)).isEqualTo(DragStep.Tap)
    }

    @Test
    fun `small jitter within the slop is still a tap`() {
        tracker.down(500f, 1200f)
        assertThat(tracker.move(510f, 1215f)).isEqualTo(DragStep.Ignore)
        assertThat(tracker.up(505f, 1210f)).isEqualTo(DragStep.Tap)
    }

    @Test
    fun `moving past the slop starts a drag and reports the vertical move`() {
        tracker.down(500f, 1200f)
        assertThat(tracker.move(500f, 1170f)).isEqualTo(DragStep.DragStart(-30))
        assertThat(tracker.move(500f, 1000f)).isEqualTo(DragStep.Drag(-200))
        assertThat(tracker.up(500f, 950f)).isEqualTo(DragStep.DragEnd(-250))
    }

    @Test
    fun `a drag that comes back to the start point is still not a tap`() {
        tracker.down(500f, 1200f)
        tracker.move(500f, 1100f)
        tracker.move(500f, 1200f)
        assertThat(tracker.up(500f, 1200f)).isEqualTo(DragStep.DragEnd(0))
    }

    @Test
    fun `a sideways slide is not a tap either`() {
        tracker.down(500f, 1200f)
        assertThat(tracker.move(560f, 1200f)).isEqualTo(DragStep.DragStart(0))
        assertThat(tracker.up(560f, 1200f)).isEqualTo(DragStep.DragEnd(0))
    }

    @Test
    fun `a jump past the slop between the last move and up is not a tap`() {
        tracker.down(500f, 1200f)
        assertThat(tracker.up(500f, 1100f)).isEqualTo(DragStep.DragEnd(-100))
    }

    @Test
    fun `cancel before a drag does nothing, cancel during a drag keeps the last spot`() {
        tracker.down(500f, 1200f)
        assertThat(tracker.cancel()).isEqualTo(DragStep.Cancel)

        tracker.down(500f, 1200f)
        tracker.move(500f, 1100f)
        assertThat(tracker.cancel()).isEqualTo(DragStep.DragEnd(-100))
    }

    @Test
    fun `events without a down are ignored`() {
        assertThat(tracker.move(1f, 1f)).isEqualTo(DragStep.Ignore)
        assertThat(tracker.up(1f, 1f)).isEqualTo(DragStep.Ignore)
        assertThat(tracker.cancel()).isEqualTo(DragStep.Ignore)
    }

    @Test
    fun `each gesture starts fresh`() {
        tracker.down(500f, 1200f)
        tracker.move(500f, 1000f)
        tracker.up(500f, 1000f)

        tracker.down(500f, 1000f)
        assertThat(tracker.up(500f, 1000f)).isEqualTo(DragStep.Tap)
    }
}
