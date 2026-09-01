package dev.amishutkin.focustube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPlanTest {

    private val feed = Bounds(0, 100, 1000, 1100)

    private fun scanOf(vararg items: FeedItem) =
        FeedScan(TargetApp.INSTAGRAM, feed, items.toList())

    private fun item(top: Int, bottom: Int, verdict: Verdict) =
        FeedItem(Bounds(0, top, 1000, bottom), verdict, Reason.NO_SIGNAL)

    @Test
    fun `an empty feed is covered end to end`() {
        assertEquals(listOf(feed), OverlayPlan.cover(scanOf()))
    }

    @Test
    fun `a kept post becomes a hole`() {
        val bands = OverlayPlan.cover(scanOf(item(400, 700, Verdict.KEEP)))
        assertEquals(listOf(Bounds(0, 100, 1000, 400), Bounds(0, 700, 1000, 1100)), bands)
    }

    @Test
    fun `hidden and unknown are both covered`() {
        for (verdict in listOf(Verdict.HIDE, Verdict.UNKNOWN)) {
            assertEquals(
                "verdict $verdict must not open a hole",
                listOf(feed),
                OverlayPlan.cover(scanOf(item(400, 700, verdict))),
            )
        }
    }

    @Test
    fun `adjacent kept posts share one hole`() {
        val bands = OverlayPlan.cover(
            scanOf(item(200, 600, Verdict.KEEP), item(600, 900, Verdict.KEEP)),
        )
        assertEquals(listOf(Bounds(0, 100, 1000, 200), Bounds(0, 900, 1000, 1100)), bands)
    }

    @Test
    fun `a kept post filling the feed leaves nothing to paint`() {
        assertTrue(OverlayPlan.cover(scanOf(item(100, 1100, Verdict.KEEP))).isEmpty())
    }

    @Test
    fun `bands never escape the feed region`() {
        val bands = OverlayPlan.cover(
            scanOf(item(-500, 300, Verdict.KEEP), item(900, 5000, Verdict.KEEP)),
        )
        assertTrue(bands.all { it.top >= feed.top && it.bottom <= feed.bottom })
    }

    @Test
    fun `a band grows while the page is moving, but never past the feed`() {
        val scan = scanOf(item(400, 700, Verdict.KEEP))
        val band = Bounds(0, 700, 1000, 1100)
        val grown = OverlayPlan.grown(band, scan)
        assertEquals("grown upwards", 650, grown.top)
        assertEquals("and clamped to the bottom of the feed", feed.bottom, grown.bottom)
    }

    @Test
    fun `a blanket cover grows freely, since there is nothing to protect`() {
        val blanket = FeedScan(
            TargetApp.INSTAGRAM, null, emptyList(),
            Surface.REELS, listOf(Bounds(0, 500, 1000, 900)),
        )
        val grown = OverlayPlan.grown(Bounds(0, 500, 1000, 900), blanket)
        assertEquals(450, grown.top)
        assertEquals(950, grown.bottom)
    }

    @Test
    fun `no feed means no overlay`() {
        assertTrue(OverlayPlan.cover(FeedScan.none(TargetApp.INSTAGRAM)).isEmpty())
    }
}
