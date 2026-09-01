package dev.amishutkin.focustube.core

import org.junit.Assert.assertEquals
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
    fun `the stories row follows its own setting`() {
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
}
