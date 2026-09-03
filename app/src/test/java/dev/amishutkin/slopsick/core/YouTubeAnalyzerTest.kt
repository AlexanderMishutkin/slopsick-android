package dev.amishutkin.slopsick.core

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
    fun `a Shorts shelf covers the videos, not just its heading`() {
        // YouTube puts the heading in one child of the feed and the videos in the next.
        // Covering the heading alone leaves the Shorts playing underneath a caption.
        val (heading, videos) = youtubeShortsShelfRows()
        val scan = YouTubeAnalyzer.analyze(
            youtubeHome(listOf("An ordinary video"), heading, videos, listOf("Another video")),
        )
        assertEquals(listOf(Bounds(0, 915, 1080, 2115)), OverlayPlan.cover(scan))
        assertEquals(Reason.SHORTS_SHELF, OverlayPlan.details(scan).single().reason)
    }

    @Test
    fun `a shelf is one region even though it is several rows`() {
        val (heading, videos) = youtubeShortsShelfRows()
        val scan = YouTubeAnalyzer.analyze(youtubeHome(heading, videos))
        assertEquals(1, scan.blackouts.size)
    }

    @Test
    fun `two separate shelves stay separate`() {
        val (heading, videos) = youtubeShortsShelfRows()
        val scan = YouTubeAnalyzer.analyze(
            youtubeHome(heading, videos, listOf("An ordinary video"), heading, videos),
        )
        assertEquals(2, scan.blackouts.size)
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

/**
 * The shape rule, which is what actually catches a Shorts shelf.
 *
 * The build on the phone this was reported from writes no "play Short" in its
 * descriptions, so the old string match covered the word "Shorts" and left the videos
 * underneath it playing. YouTube's feed is one video per row; Shorts come two or three
 * abreast in portrait, and nothing else in the feed looks like that.
 */
class YouTubeShelfShapeTest {

    @Test
    fun `a shelf with no telltale labels at all is still covered`() {
        val scan = YouTubeAnalyzer.analyze(
            youtubeFeedOf(youtubeVideoRow(189), youtubeGridRow(989), youtubeVideoRow(1844)),
        )
        assertEquals(listOf(Bounds(0, 989, 1080, 1844)), scan.blackouts)
    }

    @Test
    fun `three abreast is a shelf too`() {
        val scan = YouTubeAnalyzer.analyze(youtubeFeedOf(youtubeGridRow(189, columns = 3)))
        assertEquals(1, scan.blackouts.size)
    }

    @Test
    fun `an ordinary one-per-row video is not a shelf`() {
        val scan = YouTubeAnalyzer.analyze(
            youtubeFeedOf(youtubeVideoRow(189), youtubeVideoRow(989), youtubeVideoRow(1789)),
        )
        assertTrue(scan.blackouts.isEmpty())
    }

    @Test
    fun `the heading above a shelf is covered with it, as one region`() {
        val heading = FakeNode(
            bounds = Bounds(0, 189, 1080, 315),
            children = listOf(FakeNode(contentDesc = "Shorts", bounds = Bounds(42, 223, 183, 281))),
        )
        val scan = YouTubeAnalyzer.analyze(youtubeFeedOf(heading, youtubeGridRow(315)))
        assertEquals(listOf(Bounds(0, 189, 1080, 1170)), scan.blackouts)
    }

    @Test
    fun `a shelf never reaches over the navigation bar`() {
        // YouTube's feed rows run under the tab bar; a shelf at the bottom of the screen
        // used to be painted straight over it.
        val scan = YouTubeAnalyzer.analyze(youtubeFeedOf(youtubeGridRow(1600, height = 737)))
        assertEquals(listOf(Bounds(0, 1600, 1080, 2211)), scan.blackouts)
    }

    @Test
    fun `a row of filter chips is not a shelf`() {
        val chips = FakeNode(
            bounds = Bounds(0, 189, 1080, 315),
            children = listOf("All", "Music", "Gaming", "Live").mapIndexed { i, name ->
                FakeNode(contentDesc = name, bounds = Bounds(32 + i * 220, 210, 212 + i * 220, 294))
            },
        )
        val scan = YouTubeAnalyzer.analyze(youtubeFeedOf(chips, youtubeVideoRow(315)))
        assertTrue(scan.blackouts.isEmpty())
    }

    @Test
    fun `the captured shelf is covered even with every Shorts label stripped out`() {
        // ytshelf-unlabelled is a real capture with the heading and the "play Short"
        // suffixes deleted — the device this was reported from, reproduced.
        val scan = YouTubeAnalyzer.analyze(XmlUiNode.fixture("ytshelf-unlabelled.xml"))
        assertEquals(1, scan.blackouts.size)
        val shelf = scan.blackouts.single()
        assertTrue("the shelf should cover the tiles, not a caption", shelf.height > 800)
        assertTrue("and stop at the tab bar", shelf.bottom <= 2211)
    }

    @Test
    fun `the captured feed without a shelf is left alone`() {
        for (name in listOf("ytscroll-05.xml", "ytscroll-08.xml")) {
            val scan = YouTubeAnalyzer.analyze(XmlUiNode.fixture(name))
            assertTrue("$name: covered something in an ordinary feed", scan.blackouts.isEmpty())
        }
    }

    @Test
    fun `no capture is ever painted over YouTube's own navigation`() {
        for (name in XmlUiNode.fixtures().map { it.name }.filter { it.startsWith("yt") }) {
            val scan = YouTubeAnalyzer.analyze(XmlUiNode.fixture(name))
            for (band in scan.blackouts) {
                assertTrue("$name: $band runs into the tab bar", band.bottom <= 2211)
            }
        }
    }
}
