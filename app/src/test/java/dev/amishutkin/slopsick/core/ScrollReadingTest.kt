package dev.amishutkin.slopsick.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a scroll event's direction. The reason this exists as its own file is a bug
 * report: swiping across a carousel to see the second photo turned the post white, every
 * time, because a sideways scroll was answered with "distance unknown" and the answer to
 * an unknown distance is to cover the whole feed.
 */
class ScrollReadingTest {

    @Test
    fun `a flat sideways swipe moved the feed nowhere`() {
        assertTrue(ScrollReading.sideways(dx = 400, dy = 0))
        assertTrue(ScrollReading.sideways(dx = -400, dy = 0))
    }

    @Test
    fun `a swipe within fifteen degrees of horizontal still counts as sideways`() {
        // tan 14° ≈ 0.249, tan 16° ≈ 0.287, either side of the 15° the reader asked for.
        assertTrue(ScrollReading.sideways(dx = 1000, dy = 249))
        assertTrue(ScrollReading.sideways(dx = 1000, dy = -249))
        assertFalse(ScrollReading.sideways(dx = 1000, dy = 287))
        assertFalse(ScrollReading.sideways(dx = 1000, dy = -287))
    }

    @Test
    fun `scrolling the feed is never mistaken for a carousel`() {
        assertFalse(ScrollReading.sideways(dx = 0, dy = 600))
        assertFalse(ScrollReading.sideways(dx = 40, dy = 600))
        // A diagonal drag belongs to the feed: it is the one that actually moves posts.
        assertFalse(ScrollReading.sideways(dx = 300, dy = 300))
    }

    @Test
    fun `a view that reports no horizontal delta is not read as sideways`() {
        // Unknown must stay unknown. Guessing "nothing moved" leaves a stale cover sitting
        // over a feed that has scrolled, which shows the reader the very post being hidden.
        assertFalse(ScrollReading.sideways(dx = ScrollReading.UNSET, dy = 600))
        assertFalse(ScrollReading.sideways(dx = ScrollReading.UNSET, dy = ScrollReading.UNSET))
        assertFalse(ScrollReading.sideways(dx = 0, dy = 0))
    }

    @Test
    fun `a sideways swipe is read even when the vertical delta is missing`() {
        assertTrue(ScrollReading.sideways(dx = 500, dy = ScrollReading.UNSET))
    }
}
