package dev.amishutkin.focustube.core

/**
 * Remembers verdicts across frames.
 *
 * A single frame is often not enough to judge what is on screen. Scroll half a post off
 * the top and its header goes with it, taking the follow button and the connection
 * degree along; the analyzer is then looking at a photo and a like button and can say
 * nothing about them. Judged frame-by-frame, every scroll would black out the post you
 * are in the middle of reading — which is exactly what the first version did.
 *
 * Two things rescue it:
 *
 *  1. **Identity.** Most items carry something stable — Instagram's header description,
 *     LinkedIn's "View <name>'s profile". If this post was judged while it was whole,
 *     that verdict still applies now.
 *  2. **Geometry.** Instagram's headerless fragments carry nothing at all. But if any
 *     post appears in both this frame and the last one, the distance it moved is how far
 *     the feed scrolled — and that is enough to ask where this fragment *was* last
 *     frame, and who owned that space.
 *
 * The second rule is deliberately narrow. An earlier version reasoned by feed order
 * instead — "a fragment belongs to the post before the first one still recognisable" —
 * which is true only if no post was skipped between frames. Skip one, and a suggestion
 * inherits a followed post's verdict and is uncovered. Matching on measured distance
 * cannot make that mistake: when the frames do not line up, nothing is inherited and the
 * fragment stays covered.
 */
class FeedLedger(private val capacity: Int = 128) {

    private val verdicts = LinkedHashMap<String, Verdict>()
    private var previous: FeedScan? = null

    /** Returns [scan] with resolvable UNKNOWN items filled in from memory. */
    fun observe(scan: FeedScan): FeedScan {
        remember(scan)

        val delta = scrollDelta(previous, scan)
        val resolved = scan.items.map { item ->
            if (item.verdict != Verdict.UNKNOWN) return@map item

            item.identity?.let { verdicts[it] }?.let { return@map item.copy(verdict = it) }

            val inherited = delta?.let { inheritByPosition(item, it) }
            if (inherited != null) item.copy(verdict = inherited) else item
        }

        val result = scan.copy(items = resolved)
        previous = result
        return result
    }

    fun forget() {
        verdicts.clear()
        previous = null
    }

    private fun remember(scan: FeedScan) {
        for (item in scan.items) {
            val identity = item.identity ?: continue
            if (item.verdict == Verdict.UNKNOWN) continue
            verdicts.remove(identity)
            verdicts[identity] = item.verdict
        }
        while (verdicts.size > capacity) {
            verdicts.remove(verdicts.keys.first())
        }
    }

    /**
     * How far the feed moved between the two frames, measured on a post that appears in
     * both. Null when they share nothing, or when the posts they share disagree — which
     * happens when the list itself changed rather than scrolled.
     */
    private fun scrollDelta(before: FeedScan?, now: FeedScan): Int? {
        if (before == null || before.app != now.app) return null
        val old = before.items.mapNotNull { item -> item.identity?.let { it to item.bounds.top } }.toMap()
        val deltas = now.items.mapNotNull { item ->
            val identity = item.identity ?: return@mapNotNull null
            old[identity]?.let { item.bounds.top - it }
        }
        if (deltas.isEmpty()) return null
        val first = deltas.first()
        return if (deltas.all { kotlin.math.abs(it - first) <= TOLERANCE }) first else null
    }

    /**
     * Looks up who owned this space in the previous frame. Only a single, unambiguous
     * owner counts: if the region spanned two posts, or ran off the top of what was
     * visible, the answer is "don't know" and the caller keeps it covered.
     */
    private fun inheritByPosition(item: FeedItem, delta: Int): Verdict? {
        val before = previous ?: return null
        val feed = before.feedBounds ?: return null

        val mappedTop = maxOf(item.bounds.top - delta, feed.top)
        val mappedBottom = minOf(item.bounds.bottom - delta, feed.bottom)
        if (mappedBottom - mappedTop < MIN_EVIDENCE) return null

        val owners = before.items.filter {
            it.verdict != Verdict.UNKNOWN &&
                it.bounds.top - TOLERANCE <= mappedTop &&
                it.bounds.bottom + TOLERANCE >= mappedBottom
        }
        return owners.singleOrNull()?.verdict
    }

    private companion object {
        /** Bounds shift by a pixel or two between frames without the feed having moved. */
        const val TOLERANCE = 2

        /** Below this, the overlap is too small to be evidence of anything. */
        const val MIN_EVIDENCE = 8
    }
}
