package dev.amishutkin.focustube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAnalyzerTest {

    private val shortsTab = Bounds(216, 2211, 432, 2337)

    @Test
    fun `the Shorts tab is blocked, and the whole button not just its caption`() {
        val scan = YouTubeAnalyzer.analyze(youtubeHome(listOf("Some video")))
        assertEquals(listOf(shortsTab), OverlayPlan.block(scan))
    }

    @Test
    fun `the Shorts player is covered, leaving the navigation bar`() {
        val scan = YouTubeAnalyzer.analyze(youtubeShortsPlayer())
        assertEquals(Surface.REELS, scan.surface)
        assertEquals(listOf(Bounds(0, 63, 1080, 2211)), OverlayPlan.cover(scan))
        assertEquals(listOf(shortsTab), OverlayPlan.block(scan))
    }

    @Test
    fun `a Shorts shelf in the home feed is covered`() {
        val scan = YouTubeAnalyzer.analyze(
            youtubeHome(listOf("An ordinary video"), listOf("Shorts"), listOf("Another video")),
        )
        assertEquals(listOf(Bounds(0, 915, 1080, 1515)), OverlayPlan.cover(scan))
        assertEquals(Reason.SHORTS_SHELF, OverlayPlan.details(scan).single().reason)
    }

    @Test
    fun `an ordinary video is not a Shorts shelf because its title mentions shorts`() {
        // The rows carry no view ids, so the heading is all there is to go on, and a title
        // is in the same list of strings as the heading.
        val scan = YouTubeAnalyzer.analyze(
            youtubeHome(listOf("I wore Shorts for 30 days and this is what happened")),
        )
        assertTrue(OverlayPlan.cover(scan).isEmpty())
    }

    @Test
    fun `the rest of the home feed is left alone`() {
        val scan = YouTubeAnalyzer.analyze(youtubeHome(listOf("A"), listOf("B"), listOf("C")))
        assertTrue("YouTube's feed is not filtered post by post", OverlayPlan.cover(scan).isEmpty())
    }

    @Test
    fun `turning Shorts off leaves YouTube untouched`() {
        val off = Settings(hideShorts = false)
        assertTrue(YouTubeAnalyzer.analyze(youtubeShortsPlayer(), off).isEmpty)
        assertTrue(YouTubeAnalyzer.analyze(youtubeHome(listOf("Shorts")), off).isEmpty)
    }
}
