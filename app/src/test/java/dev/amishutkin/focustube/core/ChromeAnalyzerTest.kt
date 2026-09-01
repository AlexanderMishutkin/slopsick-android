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
        assertEquals(2, scan.items.size)
    }

    @Test
    fun `a post from an account you follow is kept`() {
        val post = ChromeAnalyzer.analyze(page).items.first()
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
        assertEquals("the feed starts at the first post, not the top of the page", 682, feed.top)
        assertEquals("and stops above the bottom navigation", 2207, feed.bottom)
        assertTrue(OverlayPlan.cover(scan).all { it.top >= 682 && it.bottom <= 2207 })
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
        assertTrue(scan.items.all { it.verdict == Verdict.KEEP })
        assertTrue(OverlayPlan.cover(scan).isEmpty())
    }
}
