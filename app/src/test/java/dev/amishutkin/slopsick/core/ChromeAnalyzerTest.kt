package dev.amishutkin.slopsick.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Against a real capture of instagram.com in Chrome, logged in. */
class ChromeAnalyzerTest {

    private val page = XmlUiNode.fixture("chromeig-01.xml")

    @Test
    fun `reads the feed on instagram dot com`() {
        val scan = ChromeAnalyzer.analyze(page)
        assertEquals(TargetApp.CHROME, scan.app)
        assertTrue(scan.hasFeed)
        assertEquals(2, scan.items.count { it.author != null })
    }

    @Test
    fun `a post from an account you follow is kept`() {
        val post = ChromeAnalyzer.analyze(page).items.first { it.reason == Reason.FOLLOWED }
        assertEquals(Reason.FOLLOWED, post.reason)
        assertEquals(Verdict.KEEP, post.verdict)
    }

    @Test
    fun `a suggested post is hidden`() {
        val post = ChromeAnalyzer.analyze(page).items.last()
        assertEquals(Reason.SUGGESTED, post.reason)
        assertEquals(Verdict.HIDE, post.verdict)
    }

    /**
     * The native analyzers cover the whole feed viewport. Here that would black out the
     * site's own header and bottom navigation, which are page content rather than app
     * chrome, and leave the reader unable to go anywhere.
     */
    @Test
    fun `the site's own navigation is left alone`() {
        val scan = ChromeAnalyzer.analyze(page)
        val feed = scan.feedBounds!!
        assertEquals("below the site's header", 333, feed.top)
        assertEquals("above the site's bottom navigation", 2207, feed.bottom)
        assertTrue(OverlayPlan.cover(scan).all { it.top >= 333 && it.bottom <= 2207 })
    }

    /**
     * An earlier version anchored the region to the first avatar it could see, so as soon
     * as a post scrolled far enough for its avatar to leave, the top of the screen went
     * uncovered — the feed "opened up" while you were scrolling past it.
     */
    @Test
    fun `the region does not shrink to whatever avatar happens to be visible`() {
        val scan = ChromeAnalyzer.analyze(page)
        assertEquals(333, scan.feedBounds!!.top)
        // The first post's avatar is at 682. Painting starts above it — at the far side
        // of the stories row, the one thing between it and the site's header — rather
        // than at the first post the scan happened to recognise.
        assertTrue(OverlayPlan.cover(scan).any { it.top < 682 })
    }

    /**
     * A feed scrolled past its first post: no stories, and a long stretch of page above
     * the first avatar. That stretch is the tail of a post whose header has gone, and it
     * is claimed as an item so the ledger and the labels know what it is.
     */
    @Test
    fun `the tail of a post scrolled past is claimed, not ignored`() {
        val fragment = ChromeAnalyzer.analyze(scrolledFeed()).items
            .single { it.reason == Reason.OFF_SCREEN_HEADER }
        assertEquals(Verdict.UNKNOWN, fragment.verdict)
        assertEquals(310, fragment.bounds.top)
        assertEquals(1400, fragment.bounds.bottom)
    }

    /**
     * The stories row is kept, exactly as it is in the app. An earlier version swept it
     * into the "everything above the first post" fragment and covered it, which the
     * native side never did.
     */
    @Test
    fun `the stories row is kept, as it is in the app`() {
        val stories = ChromeAnalyzer.analyze(page).items
            .single { it.reason == Reason.STORIES_TRAY }
        assertEquals(Verdict.KEEP, stories.verdict)
        assertEquals(346, stories.bounds.top)

        val hidden = ChromeAnalyzer.analyze(page, Settings(hideStoriesTray = true)).items
            .single { it.reason == Reason.STORIES_TRAY }
        assertEquals(Verdict.HIDE, hidden.verdict)
    }

    @Test
    fun `explore is covered wall to wall`() {
        val scan = ChromeAnalyzer.analyze(XmlUiNode.fixture("chromeweb-explore.xml"))
        assertEquals(Surface.EXPLORE, scan.surface)
        val cover = OverlayPlan.cover(scan).single()
        assertEquals(325, cover.top)
        assertEquals(2207, cover.bottom)
    }

    @Test
    fun `reels is covered wall to wall`() {
        val scan = ChromeAnalyzer.analyze(XmlUiNode.fixture("chromeweb-reels.xml"))
        assertEquals(Surface.REELS, scan.surface)
        assertTrue(OverlayPlan.cover(scan).single().bottom <= 2207)
    }

    @Test
    fun `the page is read from the address bar, not guessed`() {
        assertEquals(ChromeAnalyzer.Page.FEED, ChromeAnalyzer.pageOf("instagram.com"))
        assertEquals(ChromeAnalyzer.Page.FEED, ChromeAnalyzer.pageOf("instagram.com/"))
        assertEquals(ChromeAnalyzer.Page.EXPLORE, ChromeAnalyzer.pageOf("instagram.com/explore/"))
        assertEquals(ChromeAnalyzer.Page.REELS, ChromeAnalyzer.pageOf("instagram.com/reels/abc/"))
        // A profile or a single post is something you navigated to on purpose.
        assertEquals(ChromeAnalyzer.Page.OTHER, ChromeAnalyzer.pageOf("instagram.com/nasa/"))
        assertEquals(ChromeAnalyzer.Page.OTHER, ChromeAnalyzer.pageOf("example.com"))
        assertEquals(ChromeAnalyzer.Page.OTHER, ChromeAnalyzer.pageOf(null))
    }

    @Test
    fun `explore and reels follow their own switches`() {
        val explore = ChromeAnalyzer.analyze(
            XmlUiNode.fixture("chromeweb-explore.xml"), Settings(hideExplore = false),
        )
        assertTrue(explore.isEmpty)
        val reels = ChromeAnalyzer.analyze(
            XmlUiNode.fixture("chromeweb-reels.xml"), Settings(hideReels = false),
        )
        assertTrue(reels.isEmpty)
    }

    @Test
    fun `another site is not touched`() {
        val elsewhere = FakeNode(
            bounds = Bounds(0, 0, 1080, 2400),
            children = listOf(
                FakeNode(
                    viewId = "com.android.chrome:id/url_bar",
                    text = "example.com",
                    bounds = Bounds(210, 71, 670, 202),
                ),
                FakeNode(className = "android.webkit.WebView", bounds = Bounds(0, 210, 1080, 2339)),
            ),
        )
        assertTrue(ChromeAnalyzer.analyze(elsewhere).isEmpty)
    }

    @Test
    fun `a page with no posts on it yields nothing`() {
        val loggedOut = FakeNode(
            bounds = Bounds(0, 0, 1080, 2400),
            children = listOf(
                FakeNode(
                    viewId = "com.android.chrome:id/url_bar",
                    text = "instagram.com",
                    bounds = Bounds(210, 71, 670, 202),
                ),
                FakeNode(
                    className = "android.webkit.WebView",
                    bounds = Bounds(0, 210, 1080, 2339),
                    children = listOf(FakeNode(text = "Log in", bounds = Bounds(0, 400, 1080, 460))),
                ),
            ),
        )
        assertTrue(ChromeAnalyzer.analyze(loggedOut).isEmpty)
    }

    @Test
    fun `turning the filter off keeps the suggested post`() {
        val scan = ChromeAnalyzer.analyze(page, Settings(hideSuggested = false))
        assertTrue(scan.items.filter { it.author != null }.all { it.verdict == Verdict.KEEP })
    }

    /**
     * A feed with none of this file's words on it: the site's bars, its stories tray and
     * its post headers all have to be found by their shape alone. The labels here are
     * Spanish, which no list in the analyzer mentions.
     */
    @Test
    fun `a page in a language the analyzer does not speak still reads`() {
        val scan = ChromeAnalyzer.analyze(scrolledFeed())
        assertTrue(scan.hasFeed)
        assertEquals("under the site's own header", 310, scan.feedBounds!!.top)
        assertEquals("above the site's own navigation", 2207, scan.feedBounds!!.bottom)
        assertEquals(1, scan.items.count { it.reason == Reason.FOLLOWED })
    }

    /**
     * The page as Chrome reports it when the account's language is Russian. Nothing in
     * this capture says "profile picture", so before the shape rules the whole feed came
     * back as one unrecognised block and was covered end to end — the reader's friends
     * included, which is exactly what the filter exists not to do.
     */
    private val russian = XmlUiNode.fixture("chromeig-ru.xml")

    @Test
    fun `a Russian feed is read post by post, not as one block`() {
        val scan = ChromeAnalyzer.analyze(russian)
        assertTrue(scan.hasFeed)
        assertEquals("below the site's header", 472, scan.feedBounds!!.top)
        assertEquals("above the site's bottom navigation", 2396, scan.feedBounds!!.bottom)
        assertTrue("the feed is not one unrecognised blob",
            scan.items.none { it.reason == Reason.OFF_SCREEN_HEADER })
    }

    @Test
    fun `the post from a friend is kept on a Russian feed`() {
        val post = ChromeAnalyzer.analyze(russian).items.single { it.reason == Reason.FOLLOWED }
        assertEquals(Verdict.KEEP, post.verdict)
        assertTrue("the author was read from the header", post.author != null)
    }

    @Test
    fun `the Russian stories row is kept, and the navigation is not covered`() {
        val scan = ChromeAnalyzer.analyze(russian)
        val stories = scan.items.single { it.reason == Reason.STORIES_TRAY }
        assertEquals(Verdict.KEEP, stories.verdict)
        assertEquals(489, stories.bounds.top)
        assertEquals(846, stories.bounds.bottom)
        assertTrue(OverlayPlan.cover(scan).all { it.top >= 472 && it.bottom <= 2396 })
    }

    /**
     * A feed scrolled past its stories, labelled in a language this file has never seen.
     * The bars are rows of equal controls hugging an edge; the post header is a small
     * square at the left margin with a name beside it. That is all it takes.
     */
    private fun scrolledFeed(): UiNode {
        val nav = (0 until 5).map { i ->
            FakeNode(
                contentDesc = listOf("Inicio", "Explorar", "Publicar", "Mensajes", "Perfil")[i],
                bounds = Bounds(40 + i * 240, 2207, 180 + i * 240, 2336),
            )
        }
        return FakeNode(
            bounds = Bounds(0, 0, 1080, 2400),
            children = listOf(
                FakeNode(
                    viewId = "com.android.chrome:id/url_bar",
                    text = "instagram.com",
                    bounds = Bounds(210, 71, 670, 202),
                ),
                FakeNode(
                    className = "android.webkit.WebView",
                    bounds = Bounds(0, 210, 1080, 2339),
                    children = listOf(
                        FakeNode(contentDesc = "Notificaciones", bounds = Bounds(42, 210, 400, 310)),
                        FakeNode(
                            bounds = Bounds(28, 1400, 118, 1490),
                            children = listOf(
                                FakeNode(
                                    contentDesc = "Foto del perfil de granite.works",
                                    bounds = Bounds(28, 1400, 118, 1490),
                                ),
                            ),
                        ),
                        FakeNode(contentDesc = "granite.works", bounds = Bounds(147, 1400, 359, 1450)),
                        FakeNode(bounds = Bounds(0, 1500, 1080, 2200)),
                    ) + nav,
                ),
            ),
        )
    }
}
