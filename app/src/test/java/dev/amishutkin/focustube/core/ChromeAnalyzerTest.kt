package dev.amishutkin.focustube.core

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
        val fragment = scan.items.single { it.reason == Reason.OFF_SCREEN_HEADER }
        assertEquals("the space above the first post is covered, not ignored",
            Verdict.UNKNOWN, fragment.verdict)
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
}
