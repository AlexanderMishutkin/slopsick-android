package dev.amishutkin.slopsick.core

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Properties that must hold for every screen ever captured, rather than expectations
 * about one of them. These are the tests that would catch a change which is right about
 * the fixture it was written against and wrong about the other forty-five.
 */
class CorpusInvariantsTest {

    private fun analyze(file: File): FeedScan {
        val root = XmlUiNode.load(file)
        if (file.name.startsWith("chrome")) return ChromeAnalyzer.analyze(root)
        val linkedIn = LinkedInAnalyzer.analyze(root)
        return if (linkedIn.hasFeed) linkedIn else InstagramAnalyzer.analyze(root)
    }

    private fun corpus(): List<Pair<File, FeedScan>> =
        XmlUiNode.fixtures().map { it to analyze(it) }

    @Test
    fun `every capture is readable`() {
        val analysed = corpus().count { (_, scan) -> scan.hasFeed }
        assertTrue("expected most captures to contain a feed, got $analysed", analysed >= 40)
    }

    @Test
    fun `items stay inside the feed region`() {
        for ((file, scan) in corpus()) {
            val feed = scan.feedBounds ?: continue
            for (item in scan.items) {
                assertTrue(
                    "${file.name}: item ${item.bounds} escapes feed $feed",
                    item.bounds.top >= feed.top && item.bounds.bottom <= feed.bottom,
                )
            }
        }
    }

    @Test
    fun `cover bands do not overlap each other`() {
        for ((file, scan) in corpus()) {
            val bands = OverlayPlan.cover(scan)
            for (i in 0 until bands.size - 1) {
                assertTrue(
                    "${file.name}: bands ${bands[i]} and ${bands[i + 1]} overlap",
                    bands[i].bottom <= bands[i + 1].top,
                )
            }
        }
    }

    @Test
    fun `nothing is painted over a post you chose to see`() {
        for ((file, scan) in corpus()) {
            val kept = scan.items.filter { it.verdict == Verdict.KEEP }
            for (band in OverlayPlan.cover(scan)) {
                for (post in kept) {
                    val overlap = band.verticalOverlap(post.bounds)
                    assertTrue(
                        "${file.name}: band $band covers ${post.reason} post ${post.bounds}",
                        overlap == 0,
                    )
                }
            }
        }
    }

    /**
     * The safety property. Anything inside the feed that was not explicitly kept has to
     * end up under the overlay — an ad, a suggestion, a card still loading, or a stretch
     * of feed no item claimed at all. A filter that leaves gaps is not filtering.
     *
     * The one licensed gap is a hairline: the plan does not paint a band a few dozen
     * pixels tall, because at that size a band is the seam between two decisions rather
     * than a piece of feed, and painting it puts a flickering white stripe on the screen.
     * Nothing readable fits in one, so the run has to be both unpainted *and* shorter
     * than a painted band would be.
     */
    @Test
    fun `everything not kept is covered, bar a hairline`() {
        for ((file, scan) in corpus()) {
            val feed = scan.feedBounds ?: continue
            val kept = scan.items.filter { it.verdict == Verdict.KEEP }.map { it.bounds }
            val bands = OverlayPlan.cover(scan)

            var run = 0
            var y = feed.top
            while (y <= feed.bottom) {
                val bare = y < feed.bottom &&
                    kept.none { y >= it.top && y < it.bottom } &&
                    bands.none { y >= it.top && y < it.bottom }
                if (bare) {
                    run += 1
                } else {
                    if (run >= HAIRLINE) {
                        fail("${file.name}: $run px ending at y=$y is neither kept nor covered")
                    }
                    run = 0
                }
                y += 1
            }
        }
    }

    /** The tallest run of feed the plan is allowed to leave unpainted. */
    private val HAIRLINE = 40

    @Test
    fun `an item is never both kept and hidden`() {
        for ((file, scan) in corpus()) {
            val kept = scan.items.filter { it.verdict == Verdict.KEEP }
            val hidden = scan.items.filter { it.verdict != Verdict.KEEP }
            for (k in kept) {
                for (h in hidden) {
                    assertTrue(
                        "${file.name}: ${k.reason} ${k.bounds} overlaps ${h.reason} ${h.bounds}",
                        k.bounds.verticalOverlap(h.bounds) <= 1,
                    )
                }
            }
        }
    }

    @Test
    fun `the wrong analyzer on the wrong app finds nothing rather than guessing`() {
        for (file in XmlUiNode.fixtures().filterNot { it.name.startsWith("chrome") }) {
            val root = XmlUiNode.load(file)
            val ig = InstagramAnalyzer.analyze(root)
            val li = LinkedInAnalyzer.analyze(root)
            assertTrue(
                "${file.name}: both analyzers claim the same screen",
                !(ig.hasFeed && li.hasFeed),
            )
        }
    }
}
