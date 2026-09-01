package dev.amishutkin.focustube.core

/**
 * Turns a [FeedScan] into the rectangles the overlay should paint.
 *
 * The rule is deliberately inverted from the browser extension. The extension hides
 * specific posts, because it can edit the page. An accessibility service cannot edit
 * anything — it can only draw on top — so this covers the whole feed and cuts holes
 * where a post earned one:
 *
 *   cover  =  the feed region  −  the posts judged KEEP
 *
 * Everything else — ads, suggestions, items still loading, regions no item claimed,
 * layouts this build has never seen — is covered. A feed filter that fails open stops
 * being a feed filter without telling you; this one fails shut.
 */
object OverlayPlan {

    /** Vertical slivers thinner than this are not worth painting. */
    private const val MIN_BAND = 2

    /** Below this a rectangle cannot hold a readable label, so it does not get one. */
    private const val MIN_LABEL_HEIGHT = 140

    /** Regions to paint over. They do not intercept touches. */
    fun cover(scan: FeedScan): List<Bounds> =
        scan.blackouts.filterNot { it.isEmpty } + feedBands(scan)

    /**
     * The feed, minus the posts that earned a hole. A blackout region is not a feed of
     * things you chose, so it never gets one.
     */
    private fun feedBands(scan: FeedScan): List<Bounds> {
        val feed = scan.feedBounds ?: return emptyList()
        if (feed.isEmpty) return emptyList()

        val holes = scan.items
            .filter { it.verdict == Verdict.KEEP }
            .map { it.bounds.top.coerceAtLeast(feed.top) to it.bounds.bottom.coerceAtMost(feed.bottom) }
            .filter { (top, bottom) -> bottom - top >= MIN_BAND }
            .sortedBy { it.first }

        val bands = mutableListOf<Bounds>()
        var y = feed.top
        for ((top, bottom) in merge(holes)) {
            if (top - y >= MIN_BAND) bands += Bounds(feed.left, y, feed.right, top)
            y = maxOf(y, bottom)
        }
        if (feed.bottom - y >= MIN_BAND) bands += Bounds(feed.left, y, feed.right, feed.bottom)
        return bands
    }

    /**
     * The individual things being covered, for drawing an outline and a label on each
     * once the screen has settled. Merged bands are the right shape for painting and the
     * wrong shape for explaining: two hidden posts in a row are one band but two things.
     *
     * Anything too short to hold a line of text is left out — a sliver of a post at the
     * edge of the screen does not need a caption.
     */
    fun details(scan: FeedScan): List<FeedItem> {
        val labelled = scan.items.filter { it.isHidden && it.bounds.height >= MIN_LABEL_HEIGHT }
        return scan.blackouts.filterNot { it.isEmpty }.mapNotNull { blackout ->
            labelled.firstOrNull { it.bounds == blackout }
        }.ifEmpty { labelled }
    }

    /** Regions to paint over *and* keep from being tapped. */
    fun block(scan: FeedScan): List<Bounds> = scan.blockers.filterNot { it.isEmpty }

    private fun merge(ranges: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (ranges.isEmpty()) return emptyList()
        val out = mutableListOf(ranges.first())
        for ((top, bottom) in ranges.drop(1)) {
            val (lastTop, lastBottom) = out.last()
            if (top <= lastBottom) out[out.lastIndex] = lastTop to maxOf(lastBottom, bottom)
            else out += top to bottom
        }
        return out
    }
}
