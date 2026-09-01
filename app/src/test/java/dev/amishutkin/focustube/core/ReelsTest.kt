package dev.amishutkin.focustube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelsTest {

    @Test
    fun `the Reels player is covered whole, not judged post by post`() {
        val scan = InstagramAnalyzer.analyze(instagramReelsScreen())
        assertEquals(Surface.REELS, scan.surface)
        assertEquals(Bounds(0, 63, 1080, 2211), scan.blackout)
        assertEquals(listOf(Bounds(0, 63, 1080, 2211)), OverlayPlan.cover(scan))
    }

    @Test
    fun `the tab bar is left alone so you can get out of Reels`() {
        val covers = OverlayPlan.cover(InstagramAnalyzer.analyze(instagramReelsScreen()))
        assertTrue(
            "covering the tab bar would trap the user in Reels",
            covers.all { it.bottom <= 2211 },
        )
    }

    @Test
    fun `the Reels tab is blocked, not merely painted over`() {
        // The main overlay lets touches through so scrolling still works, so a painted-over
        // button would still open Reels when tapped blind.
        val scan = InstagramAnalyzer.analyze(instagramReelsScreen())
        assertEquals(listOf(Bounds(216, 2211, 432, 2337)), OverlayPlan.block(scan))
    }

    @Test
    fun `the Reels tab is blocked on every Instagram screen, not just the feed`() {
        // Instagram opens straight into Reels sometimes, so waiting for a feed to appear
        // before dealing with the button would miss the case that matters.
        val scan = InstagramAnalyzer.analyze(instagramOtherScreen())
        assertEquals(Surface.OTHER, scan.surface)
        assertEquals(listOf(Bounds(216, 2211, 432, 2337)), OverlayPlan.block(scan))
        assertTrue("a screen with a blocker is not an empty scan", !scan.isEmpty)
    }

    @Test
    fun `every captured feed screen has its Reels tab blocked`() {
        val instagram = XmlUiNode.fixtures().filter { it.name.startsWith("igscroll") }
        assertTrue(instagram.isNotEmpty())
        for (file in instagram) {
            val scan = InstagramAnalyzer.analyze(XmlUiNode.load(file))
            assertEquals("${file.name}: expected the Reels tab to be blocked", 1, scan.blockers.size)
        }
    }

    @Test
    fun `turning Reels off leaves it alone entirely`() {
        val off = Settings(hideReels = false)

        val reels = InstagramAnalyzer.analyze(instagramReelsScreen(), off)
        assertNull(reels.blackout)
        assertTrue(reels.blockers.isEmpty())
        assertTrue(OverlayPlan.cover(reels).isEmpty())

        val feed = InstagramAnalyzer.analyze(XmlUiNode.fixture("igscroll-01.xml"), off)
        assertTrue(feed.blockers.isEmpty())
    }

    @Test
    fun `a Reels screen is still Reels when the feed is also in the tree`() {
        // Instagram keeps the feed fragment alive behind the Reels tab, so a scan that
        // simply looked for a feed list first would classify this as a feed.
        val withFeedBehind = FakeNode(
            bounds = Bounds(0, 0, 1080, 2400),
            children = listOf(
                FakeNode(
                    viewId = "android:id/list",
                    className = "androidx.recyclerview.widget.RecyclerView",
                    bounds = Bounds(0, 63, 1080, 2211),
                ),
                instagramReelsScreen(),
            ),
        )
        assertEquals(Surface.REELS, InstagramAnalyzer.analyze(withFeedBehind).surface)
    }
}
