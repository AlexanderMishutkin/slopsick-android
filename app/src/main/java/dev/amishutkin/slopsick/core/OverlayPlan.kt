package dev.amishutkin.slopsick.core

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

    /** How much a band grows while the page is moving, as a fraction of its height. */
    private const val SCROLL_MARGIN = 0.25

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

    /**
     * A band grown by [SCROLL_MARGIN] for use while the page is moving.
     *
     * The overlay is always a frame or two behind a scrolling page, and the difference
     * shows as a strip of the very thing being covered — a Short playing along the edge
     * of its own cover. Growing the band absorbs that, at the cost of briefly covering a
     * little more than necessary, which is the right way round.
     *
     * It never grows past the feed, so a kept post cannot be swallowed by its neighbour's
     * margin; on a blanket cover there is nothing to protect and it grows freely.
     */
    fun grown(band: Bounds, scan: FeedScan): Bounds {
        val margin = (band.height * SCROLL_MARGIN / 2).toInt()
        val limit = scan.feedBounds
        val top = band.top - margin
        val bottom = band.bottom + margin
        return Bounds(
            band.left,
            if (limit != null) maxOf(top, limit.top) else top,
            band.right,
            if (limit != null) minOf(bottom, limit.bottom) else bottom,
        )
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
