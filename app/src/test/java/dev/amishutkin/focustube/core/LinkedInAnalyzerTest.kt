package dev.amishutkin.focustube.core

import org.junit.Assert.assertEquals
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
    fun `what your network reacted to is kept by default and hidden on request`() {
        val kept = scan("liscroll-05.xml").items.single { it.reason == Reason.NETWORK_ACTIVITY }
        assertEquals(Verdict.KEEP, kept.verdict)

        val hidden = scan("liscroll-05.xml", Settings(hideNetworkActivity = true))
            .items.single { it.reason == Reason.NETWORK_ACTIVITY }
        assertEquals(Verdict.HIDE, hidden.verdict)
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
        val item = scan("liscroll-08.xml").items.single()
        assertEquals(Reason.NO_SIGNAL, item.reason)
        assertEquals(Verdict.UNKNOWN, item.verdict)
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
}
