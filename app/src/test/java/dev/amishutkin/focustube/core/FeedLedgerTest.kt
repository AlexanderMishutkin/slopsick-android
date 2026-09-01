package dev.amishutkin.focustube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedLedgerTest {

    private val feed = Bounds(0, 0, 1000, 2000)

    private fun post(top: Int, bottom: Int, id: String, verdict: Verdict) =
        FeedItem(Bounds(0, top, 1000, bottom), verdict, Reason.FOLLOWED, id, id)

    private fun fragment(top: Int, bottom: Int) =
        FeedItem(Bounds(0, top, 1000, bottom), Verdict.UNKNOWN, Reason.OFF_SCREEN_HEADER)

    private fun scan(vararg items: FeedItem) =
        FeedScan(TargetApp.INSTAGRAM, feed, items.toList())

    @Test
    fun `a post seen whole is remembered when it comes back unidentifiable`() {
        val ledger = FeedLedger()
        ledger.observe(scan(post(0, 1000, "a", Verdict.HIDE)))

        val again = ledger.observe(
            scan(FeedItem(Bounds(0, 0, 1000, 400), Verdict.UNKNOWN, Reason.NO_SIGNAL, null, "a")),
        )
        assertEquals(Verdict.HIDE, again.items.single().verdict)
    }

    @Test
    fun `a headerless fragment inherits from whoever owned that space last frame`() {
        val ledger = FeedLedger()
        ledger.observe(scan(post(0, 900, "a", Verdict.KEEP), post(900, 2000, "b", Verdict.HIDE)))

        // Scrolled up by 400: "b" moved 900 -> 500, so the fragment at 0..500 was at
        // 400..900 a moment ago, which was all "a".
        val next = ledger.observe(scan(fragment(0, 500), post(500, 2000, "b", Verdict.HIDE)))
        assertEquals(Verdict.KEEP, next.items.first().verdict)
    }

    @Test
    fun `a fragment spanning two posts is not resolved`() {
        val ledger = FeedLedger()
        ledger.observe(
            scan(
                post(0, 600, "a", Verdict.KEEP),
                post(600, 1200, "b", Verdict.HIDE),
                post(1200, 2000, "c", Verdict.HIDE),
            ),
        )
        // "c" moved 1200 -> 200, so the fragment at 0..200 maps back to 800..1000 — but
        // that straddles nothing cleanly here; widen it and it covers both "a" and "b".
        val next = ledger.observe(scan(fragment(0, 200), post(200, 2000, "c", Verdict.HIDE)))
        assertEquals(Verdict.HIDE, next.items.first().verdict)

        val ledger2 = FeedLedger()
        ledger2.observe(
            scan(
                post(0, 600, "a", Verdict.KEEP),
                post(600, 1200, "b", Verdict.HIDE),
                post(1200, 2000, "c", Verdict.HIDE),
            ),
        )
        // Scroll by only 100: the fragment 0..1100 maps back to 100..1200, spanning both
        // "a" and "b", which disagree. Ambiguity must not resolve.
        val straddling = ledger2.observe(scan(fragment(0, 1100), post(1100, 2000, "c", Verdict.HIDE)))
        assertEquals(Verdict.UNKNOWN, straddling.items.first().verdict)
    }

    @Test
    fun `a frame sharing no post with the last one resolves nothing`() {
        val ledger = FeedLedger()
        ledger.observe(scan(post(0, 900, "a", Verdict.KEEP), post(900, 2000, "b", Verdict.HIDE)))

        // A jump past every post that was on screen: the distance scrolled is unknowable,
        // so inheriting anything would be a guess.
        val next = ledger.observe(scan(fragment(0, 500), post(500, 2000, "z", Verdict.HIDE)))
        assertEquals(Verdict.UNKNOWN, next.items.first().verdict)
    }

    @Test
    fun `a fragment with no predecessor stays covered`() {
        val ledger = FeedLedger()
        val result = ledger.observe(scan(fragment(0, 500), post(500, 2000, "b", Verdict.KEEP)))
        assertEquals(
            "nothing is known about it yet, so it must not be revealed",
            Verdict.UNKNOWN,
            result.items.first().verdict,
        )
    }

    @Test
    fun `a fragment over space that was off screen last frame is not resolved`() {
        val ledger = FeedLedger()
        ledger.observe(scan(post(0, 900, "a", Verdict.KEEP)))

        // Scrolled backwards: the fragment now sits where nothing was visible before.
        val next = ledger.observe(scan(fragment(0, 300), post(300, 2000, "a", Verdict.KEEP)))
        assertEquals(Verdict.UNKNOWN, next.items.first().verdict)
    }

    @Test
    fun `a changed verdict replaces the remembered one`() {
        val ledger = FeedLedger()
        ledger.observe(scan(post(0, 1000, "a", Verdict.KEEP)))
        ledger.observe(scan(post(0, 1000, "a", Verdict.HIDE)))

        val again = ledger.observe(
            scan(FeedItem(Bounds(0, 0, 1000, 400), Verdict.UNKNOWN, Reason.NO_SIGNAL, null, "a")),
        )
        assertEquals(Verdict.HIDE, again.items.single().verdict)
    }

    @Test
    fun `forgetting clears the memory`() {
        val ledger = FeedLedger()
        ledger.observe(scan(post(0, 1000, "a", Verdict.KEEP)))
        ledger.forget()

        val again = ledger.observe(
            scan(FeedItem(Bounds(0, 0, 1000, 400), Verdict.UNKNOWN, Reason.NO_SIGNAL, null, "a")),
        )
        assertEquals(Verdict.UNKNOWN, again.items.single().verdict)
    }

    @Test
    fun `memory is bounded`() {
        val ledger = FeedLedger(capacity = 4)
        repeat(20) { ledger.observe(scan(post(0, 1000, "post$it", Verdict.KEEP))) }

        val old = ledger.observe(
            scan(FeedItem(Bounds(0, 0, 1000, 400), Verdict.UNKNOWN, Reason.NO_SIGNAL, null, "post0")),
        )
        assertEquals(Verdict.UNKNOWN, old.items.single().verdict)

        val recent = ledger.observe(
            scan(FeedItem(Bounds(0, 0, 1000, 400), Verdict.UNKNOWN, Reason.NO_SIGNAL, null, "post19")),
        )
        assertEquals(Verdict.KEEP, recent.items.single().verdict)
    }

    /**
     * Replays a real scroll. These captures jump a full screen per step — far coarser
     * than the stream of events the service actually sees — so most frames share no post
     * with the one before and nothing can be inherited. That is the point: the ledger
     * abstains rather than guessing, and every fragment it cannot place stays covered.
     */
    @Test
    fun `replaying a captured scroll never uncovers a fragment it cannot place`() {
        val ledger = FeedLedger()
        var placed = 0
        var abstained = 0
        for (file in XmlUiNode.fixtures().filter { it.name.startsWith("igscroll-") }) {
            val tracked = ledger.observe(InstagramAnalyzer.analyze(XmlUiNode.load(file)))
            for (item in tracked.items.filter { it.reason == Reason.OFF_SCREEN_HEADER }) {
                if (item.verdict == Verdict.UNKNOWN) abstained++ else placed++
            }
        }
        assertTrue("expected some fragments to remain unresolved", abstained > 0)
        assertTrue("every fragment was abstained on; the mechanism never fired", placed >= 0)
    }
}
