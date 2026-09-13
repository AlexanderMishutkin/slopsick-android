package dev.amishutkin.slopsick.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramAnalyzerTest {

    private fun scan(name: String, settings: Settings = Settings()) =
        InstagramAnalyzer.analyze(XmlUiNode.fixture(name), settings)

    @Test
    fun `finds the feed on an unscrolled frame`() {
        val result = scan("igscroll-01.xml")
        assertEquals(TargetApp.INSTAGRAM, result.app)
        assertTrue(result.hasFeed)
        assertTrue(result.items.isNotEmpty())
    }

    @Test
    fun `the covered region stops short of Instagram's own chrome`() {
        val result = scan("igscroll-01.xml")
        val feed = result.feedBounds!!
        // The feed RecyclerView starts at y=63, behind the floating action bar, which ends
        // at 210. Painting from 63 would black out Instagram's toolbar and strand the user.
        assertEquals(210, feed.top)
        // ...and stop above the tab bar, or they cannot navigate away either.
        assertEquals(2211, feed.bottom)
    }

    @Test
    fun `a post from an account you follow is kept`() {
        val post = scan("igscroll-01.xml").items.single { it.reason == Reason.FOLLOWED }
        assertEquals(Verdict.KEEP, post.verdict)
        assertEquals("indigo.set53", post.author)
    }

    @Test
    fun `a suggested post is hidden`() {
        val post = scan("igscroll-12.xml").items.single { it.reason == Reason.SUGGESTED }
        assertEquals(Verdict.HIDE, post.verdict)
    }

    /**
     * The signal is the presence of `inline_follow_button`, not any word on screen. These
     * seven captures are suggested posts whose `secondary_label` says something else
     * entirely — an audio track, "Translate with AI", a channel name. Reading the label
     * instead would pass every other test in this file and miss all seven.
     */
    @Test
    fun `suggested posts are found even when the label says something else`() {
        val misleading = listOf(
            "igscroll-02.xml", "igscroll-14.xml", "igscroll2-06.xml", "igscroll2-11.xml",
            "igscroll2-13.xml", "igscroll2-15.xml", "igscroll2-16.xml",
        )
        for (fixture in misleading) {
            val suggested = scan(fixture).items.filter { it.reason == Reason.SUGGESTED }
            assertTrue("$fixture: expected a suggested post", suggested.isNotEmpty())
            assertTrue("$fixture: should be hidden", suggested.all { it.verdict == Verdict.HIDE })
        }
    }

    @Test
    fun `turning the filter off keeps suggested posts`() {
        val result = scan("igscroll-12.xml", Settings(hideSuggested = false))
        assertTrue(result.items.filter { it.reason == Reason.SUGGESTED }
            .all { it.verdict == Verdict.KEEP })
    }

    @Test
    fun `the stories row follows its own setting, and is kept by default`() {
        // Stories are people you followed on purpose. Kept in the app and on the web.
        assertEquals(
            Verdict.KEEP,
            scan("igscroll-01.xml").items.single { it.reason == Reason.STORIES_TRAY }.verdict,
        )
        assertEquals(
            Verdict.HIDE,
            scan("igscroll-01.xml", Settings(hideStoriesTray = true))
                .items.single { it.reason == Reason.STORIES_TRAY }.verdict,
        )
    }

    @Test
    fun `the tail of a post whose header scrolled away is one item, not several`() {
        val fragments = scan("igscroll-03.xml").items.filter {
            it.reason == Reason.OFF_SCREEN_HEADER
        }
        assertEquals(1, fragments.size)
        assertEquals(Verdict.UNKNOWN, fragments.single().verdict)
        assertNull("a fragment has nothing stable to key on", fragments.single().identity)
    }

    @Test
    fun `identified posts carry an identity the ledger can key on`() {
        val posts = scan("igscroll-06.xml").items.filter { it.verdict != Verdict.UNKNOWN }
        assertTrue(posts.isNotEmpty())
        for (post in posts.filter { it.reason != Reason.STORIES_TRAY }) {
            assertNotNull(post.identity)
        }
        // Two different posts must not collide.
        val identities = posts.mapNotNull { it.identity }
        assertEquals(identities.size, identities.toSet().size)
    }

    @Test
    fun `a screen that is not a feed yields nothing`() {
        val empty = InstagramAnalyzer.analyze(XmlUiNode.fixture("liscroll-01.xml"))
        assertTrue(!empty.hasFeed && empty.items.isEmpty())
    }
    /**
     * The line Instagram draws where the feed you chose ends and its recommendations
     * begin. It was being swept into the "everything above the first post" fragment and
     * painted over, which left no way to tell a filter that is working from a feed that
     * has gone blank — the reader's own reason for wanting it back.
     */
    @Test
    fun `the caught-up line is left visible, and the recommendations under it are not`() {
        val scan = InstagramAnalyzer.analyze(XmlUiNode.fixture("igcaughtup.xml"))
        val line = scan.items.single { it.reason == Reason.FEED_MODULE }
        assertEquals(Verdict.KEEP, line.verdict)
        assertEquals("from the top of its card", 715, line.bounds.top)
        assertEquals("down to the bottom of the bar, not the heading under it", 877, line.bounds.bottom)

        val suggested = scan.items.single { it.reason == Reason.SUGGESTED }
        assertEquals(Verdict.HIDE, suggested.verdict)
        assertEquals(1055, suggested.bounds.top)

        val cover = OverlayPlan.cover(scan)
        assertTrue("nothing is painted over it", cover.none { it.verticalOverlap(line.bounds) > 0 })
        assertTrue("the heading below it still is", cover.any { it.top == 877 })
    }

    @Test
    fun `a profile page is not the feed, and none of it is covered`() {
        // A profile's post grid is a RecyclerView carrying the id `list` — the same id the
        // home feed's list carries. Three bug reports off the phone were somebody's whole
        // profile under one cover, because a grid of thumbnails has no post headers in it.
        for (capture in listOf("igprofile-01.xml", "igprofile-02.xml", "igprofile-03.xml")) {
            val result = scan(capture)
            assertFalse(capture, result.hasFeed)
            assertTrue(capture, OverlayPlan.cover(result).isEmpty())
            assertEquals(capture, Surface.OTHER, result.surface)
        }
    }

    @Test
    fun `a post whose header has scrolled off is not taken for a friend's`() {
        // Reported as "left something visible that should be hidden". The post at the top
        // is a suggestion — its header still says so — but the header is half off screen,
        // Instagram has stopped reporting the follow button, and a missing follow button
        // is the whole basis for calling a post a friend's.
        val result = scan("igkept-01.xml")
        val top = result.items.first()
        assertEquals(Verdict.UNKNOWN, top.verdict)
        assertEquals(Reason.OFF_SCREEN_HEADER, top.reason)
        // The identity survives, so the ledger can still recognise it from an earlier frame.
        assertEquals("Gus Tanner", top.author)
        assertTrue(OverlayPlan.cover(result).any { it.top <= 138 })
    }

    @Test
    fun `a header fully on screen is still read as a friend's post`() {
        // The other half of the rule above: this must not turn every post into UNKNOWN.
        val post = scan("igscroll-01.xml").items.single { it.reason == Reason.FOLLOWED }
        assertEquals(Verdict.KEEP, post.verdict)
    }
}
