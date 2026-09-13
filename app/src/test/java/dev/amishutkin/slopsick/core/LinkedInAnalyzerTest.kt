package dev.amishutkin.slopsick.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkedInAnalyzerTest {

    private fun scan(name: String, settings: Settings = Settings()) =
        LinkedInAnalyzer.analyze(XmlUiNode.fixture(name), settings)

    @Test
    fun `finds the feed through the only view id LinkedIn still exposes`() {
        val result = scan("liscroll-03.xml")
        assertEquals(TargetApp.LINKEDIN, result.app)
        assertTrue(result.hasFeed)
        // One lazy column child is exactly one feed item — easier grouping than Instagram.
        assertEquals(2, result.items.size)
    }

    @Test
    fun `a post the feed guessed at is hidden`() {
        val guess = scan("liscroll-03.xml").items.first()
        assertEquals(Reason.SUGGESTED, guess.reason)
        assertEquals(Verdict.HIDE, guess.verdict)
    }

    @Test
    fun `what your network reacted to goes by default, and can be kept`() {
        // A stranger's post that a connection happened to like is a post the feed chose
        // for you — a suggestion wearing a friend's name.
        val hidden = scan("liscroll-05.xml").items.single { it.reason == Reason.NETWORK_ACTIVITY }
        assertEquals(Verdict.HIDE, hidden.verdict)

        val kept = scan("liscroll-05.xml", Settings(hideNetworkActivity = false))
            .items.single { it.reason == Reason.NETWORK_ACTIVITY }
        assertEquals(Verdict.KEEP, kept.verdict)
    }

    @Test
    fun `interstitial cards are not posts and go by default`() {
        val modules = scan("liscroll-01.xml").items.filter { it.reason == Reason.FEED_MODULE }
        assertTrue(modules.isNotEmpty())
        assertTrue(modules.all { it.verdict == Verdict.HIDE })

        val kept = scan("liscroll-01.xml", Settings(hideFeedModules = false))
        assertTrue(kept.items.filter { it.reason == Reason.FEED_MODULE }
            .all { it.verdict == Verdict.KEEP })
    }

    @Test
    fun `a post with an author but no follow control is treated as a relationship`() {
        val post = scan("liscroll-04.xml").items.first()
        assertEquals(Reason.CONNECTION, post.reason)
        assertEquals(Verdict.KEEP, post.verdict)
    }

    @Test
    fun `a card with nothing on it at all is covered, not guessed at`() {
        val item = LinkedInAnalyzer.analyze(linkedInScreen("Show more")).items.single()
        assertEquals(Reason.NO_SIGNAL, item.reason)
        assertEquals(Verdict.UNKNOWN, item.verdict)
    }

    /**
     * Every LinkedIn tab is a lazy column, so without checking which one is current this
     * covered job listings and search results — neither of which is the feed choosing
     * things for you.
     */
    @Test
    fun `only the feed tab is touched`() {
        val jobs = LinkedInAnalyzer.analyze(
            linkedInScreen("Software Engineer", "Apply", onFeed = false),
        )
        assertTrue(!jobs.hasFeed && jobs.items.isEmpty())
    }

    @Test
    fun `a screen with no tab bar at all is left alone`() {
        // Not being able to tell which screen this is, is not a licence to paint over it.
        val unknown = LinkedInAnalyzer.analyze(
            FakeNode(
                bounds = Bounds(0, 0, 1000, 1000),
                children = listOf(FakeNode(viewId = "sdui:lazyColumn", bounds = Bounds(0, 0, 1000, 1000))),
            ),
        )
        assertTrue(!unknown.hasFeed)
    }

    /**
     * Identity keys the cross-frame memory, so a weak one is worse than none: two posts
     * sharing a key would inherit each other's verdicts. Only a name lifted from
     * "View <name>'s profile" qualifies.
     */
    @Test
    fun `identity comes only from a profile link`() {
        val withProfile = scan("liscroll-04.xml").items.first()
        assertNotNull(withProfile.identity)
        assertTrue(withProfile.identity!!.startsWith("li:"))

        val timestampOnly = scan("liscroll-07.xml").items.last()
        assertNull(
            "a timestamp like \"13h\" must never become an identity",
            timestampOnly.identity,
        )
    }

    @Test
    fun `identities are unique within a frame`() {
        for (file in XmlUiNode.fixtures().filter { it.name.startsWith("liscroll-0") }) {
            val ids = LinkedInAnalyzer.analyze(XmlUiNode.load(file)).items.mapNotNull { it.identity }
            assertEquals(file.name, ids.size, ids.toSet().size)
        }
    }

    // --- the same feed, in Russian ---------------------------------------------------
    //
    // Three captures from a phone running LinkedIn in Russian, where every post came back
    // KEEP: none of the English signals matched, and the fallback that keeps a post on the
    // strength of having an author was reading "13 ч." out of a timestamp.

    @Test
    fun `what your network reacted to is recognised in Russian too`() {
        val liked = scan("liru-03.xml").items.filter { it.reason == Reason.NETWORK_ACTIVITY }
        assertEquals("both posts on the screen are ones someone reacted to", 2, liked.size)
        assertTrue(liked.all { it.verdict == Verdict.HIDE })
        assertTrue("the post itself, not just its header", liked.any { it.bounds.height > 1000 })
    }

    @Test
    fun `every Russian capture has something hidden in it`() {
        for (name in listOf("liru-01.xml", "liru-02.xml", "liru-03.xml")) {
            val scan = scan(name)
            assertTrue("$name: reads as a feed", scan.hasFeed)
            assertTrue(
                "$name: nothing hidden — the whole feed came back kept",
                scan.items.any { it.isHidden },
            )
        }
    }

    @Test
    fun `the Russian degree marker is read like the English one`() {
        val degrees = scan("liru-03.xml").items.map { it.reason }
        assertTrue(degrees.contains(Reason.NETWORK_ACTIVITY))
        assertTrue(scan("liru-03.xml").items.none { it.reason == Reason.CONNECTION })
    }

    /**
     * The fail-open path this closed. A timestamp is not an author, and a feed whose
     * language none of the lists cover is a feed of nothing but timestamps: keeping every
     * post in one is worse than covering it, because the reader cannot see it happening.
     */
    @Test
    fun `a post is not kept on the strength of a timestamp`() {
        val screen = linkedInScreen("13 ч. • Доступность: все", "Показать перевод")
        val item = LinkedInAnalyzer.analyze(screen).items.single()
        assertEquals(Verdict.UNKNOWN, item.verdict)
        assertEquals(Reason.NO_SIGNAL, item.reason)
    }

    @Test
    fun `nothing is painted while the navigation drawer is open`() {
        // Reported from the phone as "I can't reach my own profile". The verdicts were
        // right and the feed was still there in the tree — behind a drawer holding the
        // profile, the settings and every other way out of the app.
        val result = LinkedInAnalyzer.analyze(XmlUiNode.fixture("lidrawer-01.xml"))
        assertFalse(result.hasFeed)
        assertTrue(OverlayPlan.cover(result).isEmpty())
    }
}
