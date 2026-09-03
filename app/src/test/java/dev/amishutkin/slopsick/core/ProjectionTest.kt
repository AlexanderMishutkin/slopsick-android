package dev.amishutkin.slopsick.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the overlay paints between scans, while the feed is moving.
 *
 * Reading the tree costs too much to do per frame, so a scroll event's own pixel count
 * is used to move the last scan's holes instead. The property that matters is not that
 * the holes land exactly right — they cannot, it is an extrapolation — but that when
 * they are wrong they are wrong towards covering more.
 */
class ProjectionTest {

    private val feed = Bounds(0, 200, 1080, 2200)

    private fun scan(vararg items: FeedItem) = FeedScan(
        app = TargetApp.INSTAGRAM,
        feedBounds = feed,
        items = items.toList(),
        safe = Bounds(0, 100, 1080, 2211),
    )

    private fun kept(top: Int, bottom: Int) =
        FeedItem(Bounds(0, top, 1080, bottom), Verdict.KEEP, Reason.FOLLOWED)

    private fun hidden(top: Int, bottom: Int) =
        FeedItem(Bounds(0, top, 1080, bottom), Verdict.HIDE, Reason.SUGGESTED)

    @Test
    fun `a kept post keeps its hole as it scrolls away`() {
        val before = scan(kept(600, 1400))
        val bands = OverlayPlan.project(before, dy = 300, drift = 300)!!
        // The hole has moved up with the post rather than being painted over.
        val holes = gapsIn(bands, feed)
        assertEquals(1, holes.size)
        assertTrue("hole should have moved up by about 300", holes.single().top in 320..360)
    }

    @Test
    fun `the hole is trimmed, not stretched, as the distance grows`() {
        val before = scan(kept(600, 1400))
        val near = gapsIn(OverlayPlan.project(before, 100, 100)!!, feed).single()
        val far = gapsIn(OverlayPlan.project(before, 100, 900)!!, feed).single()
        assertTrue(
            "a longer scroll since the last scan should leave a smaller hole",
            far.height < near.height,
        )
    }

    @Test
    fun `a hidden post is never given a hole by the projection`() {
        val before = scan(hidden(600, 1400))
        val bands = OverlayPlan.project(before, 300, 300)!!
        assertEquals(listOf(feed), bands)
    }

    @Test
    fun `scrolled far enough since the last scan, the projection gives up`() {
        assertNull(OverlayPlan.project(scan(kept(600, 1400)), 300, 9000))
    }

    @Test
    fun `a hole worn away to nothing is dropped rather than left as a sliver`() {
        val before = scan(kept(600, 700))
        val bands = OverlayPlan.project(before, 100, 400)!!
        assertEquals("nothing should be left uncovered", listOf(feed), bands)
    }

    @Test
    fun `a blackout moves with the feed and stops at the navigation bar`() {
        val shelf = Bounds(0, 1400, 1080, 2100)
        val before = FeedScan(
            app = TargetApp.YOUTUBE,
            feedBounds = null,
            items = emptyList(),
            blackouts = listOf(shelf),
            safe = Bounds(0, 189, 1080, 2211),
        )
        // Scrolling back up pushes the shelf down, towards the tab bar.
        val bands = OverlayPlan.project(before, dy = -200, drift = 200)!!
        assertEquals(1, bands.size)
        assertTrue("must not reach past the navigation bar", bands.single().bottom <= 2211)
    }

    @Test
    fun `nothing to project from is said so, rather than guessed at`() {
        val nothing = FeedScan(TargetApp.INSTAGRAM, null, emptyList())
        assertNull(OverlayPlan.project(nothing, 100, 100))
    }

    /** The stretches of [region] that no band covers. */
    private fun gapsIn(bands: List<Bounds>, region: Bounds): List<Bounds> {
        val out = mutableListOf<Bounds>()
        var y = region.top
        for (band in bands.sortedBy { it.top }) {
            if (band.top > y) out += Bounds(region.left, y, region.right, band.top)
            y = maxOf(y, band.bottom)
        }
        if (y < region.bottom) out += Bounds(region.left, y, region.right, region.bottom)
        return out
    }
}
