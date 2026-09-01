package dev.amishutkin.focustube.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every LinkedIn signal is a string, and the post's own text sits in the same list of
 * strings the signals are matched against. The captured fixtures cannot catch a
 * misclassification caused by post prose, because the anonymiser replaces long bodies
 * with a placeholder — so these screens are built by hand.
 */
class LinkedInBodyTextTest {

    private fun verdictOf(vararg labels: String) =
        LinkedInAnalyzer.analyze(linkedInScreen(*labels)).items.single()

    private val body =
        "Delighted to share that I have been promoted to Senior Engineer this week, and " +
            "I commented on a few posts about it, which is Sponsored by nobody at all. " +
            "Thank you to everyone who supported me through the last two years here."

    @Test
    fun `a post is not an advert because its text contains the word Promoted`() {
        val item = verdictOf("View Alba Rivers's profile", "Alba Rivers", "• 1st", body)
        assertEquals(Reason.CONNECTION, item.reason)
        assertEquals(Verdict.KEEP, item.verdict)
    }

    @Test
    fun `a real Promoted marker still counts`() {
        val item = verdictOf("View Alba Rivers's profile", "Promoted", "• 1st")
        assertEquals(Reason.PROMOTED, item.reason)
        assertEquals(Verdict.HIDE, item.verdict)
    }

    @Test
    fun `a post is not network activity because its text contains commented`() {
        val item = verdictOf("View Alba Rivers's profile", "Alba Rivers", "• 1st", body)
        assertEquals(Reason.CONNECTION, item.reason)
    }

    @Test
    fun `a real activity line still counts`() {
        val item = verdictOf("Bruno Vale commented", "View Alba Rivers's profile", "• 3rd+")
        assertEquals(Reason.NETWORK_ACTIVITY, item.reason)
    }

    @Test
    fun `a card with only body text and no author is covered`() {
        val item = verdictOf(body)
        assertEquals(Reason.NO_SIGNAL, item.reason)
        assertEquals(Verdict.UNKNOWN, item.verdict)
    }
}
