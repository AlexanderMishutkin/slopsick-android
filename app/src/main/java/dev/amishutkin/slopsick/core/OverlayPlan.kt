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

    /** Vertical slivers thinner than this are not worth reasoning about. */
    private const val MIN_BAND = 2

    /**
     * Thinner than this and a band is not painted at all.
     *
     * A band is the gap between two things the scan decided to keep, and when those two
     * decisions disagree by a few pixels — a post's bounds reported one frame late, a
     * hole projected a little short — the gap is a stripe of nothing rather than a piece
     * of feed. On a phone those arrive as thin white polygons flicking in and out at the
     * seams between posts, which is noise pretending to be a cover. No post, ad or
     * suggestion is this short, so a band this thin is always the artefact and never the
     * thing: the cost of dropping it is a hairline of feed, and the cost of drawing it is
     * a screen that looks broken.
     */
    private const val MIN_COVER_BAND = 40

    /** Below this a rectangle cannot hold a readable label, so it does not get one. */
    private const val MIN_LABEL_HEIGHT = 140

    /** How much a band grows while the page is moving, as a fraction of its height. */
    private const val SCROLL_MARGIN = 0.25

    /**
     * How much of the distance scrolled since the last scan is given up as margin around
     * a projected hole. A tenth of it absorbs the rounding and the frame the overlay is
     * behind by, without eating the post it is protecting.
     */
    private const val DRIFT_MARGIN = 0.1

    /** Past this much scrolling since the last scan, projection stops being evidence. */
    private const val MAX_DRIFT = 2.0

    /** A hole worn thinner than this by margins is not worth keeping open. */
    private const val MIN_HOLE = 64

    /** Regions to paint over. They do not intercept touches. */
    fun cover(scan: FeedScan): List<Bounds> =
        scan.blackouts.filterNot { it.isEmpty } + feedBands(scan.feedBounds, keptIn(scan))

    private fun keptIn(scan: FeedScan): List<Bounds> =
        scan.items.filter { it.verdict == Verdict.KEEP }.map { it.bounds }

    /**
     * The feed, minus the posts that earned a hole. A blackout region is not a feed of
     * things you chose, so it never gets one.
     */
    private fun feedBands(feed: Bounds?, kept: List<Bounds>): List<Bounds> {
        if (feed == null || feed.isEmpty) return emptyList()

        val holes = kept
            .map { it.top.coerceAtLeast(feed.top) to it.bottom.coerceAtMost(feed.bottom) }
            .filter { (top, bottom) -> bottom - top >= MIN_BAND }
            .sortedBy { it.first }

        val bands = mutableListOf<Bounds>()
        var y = feed.top
        for ((top, bottom) in merge(holes)) {
            if (top - y >= MIN_COVER_BAND) bands += Bounds(feed.left, y, feed.right, top)
            y = maxOf(y, bottom)
        }
        if (feed.bottom - y >= MIN_COVER_BAND) bands += Bounds(feed.left, y, feed.right, feed.bottom)
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

    /**
     * Where the cover should be *right now*, given the last scan and how far the feed has
     * scrolled since it was taken — without reading the tree again.
     *
     * This is what makes scrolling feel immediate. Reading an accessibility tree costs
     * tens of milliseconds and cannot be done per frame, so between scans the overlay
     * used to fall back to covering the entire feed: scroll a pixel and the post you were
     * reading went black until the next scan caught up. But a scroll event carries the
     * exact number of pixels the list moved, and moving a rectangle by a known distance
     * needs no tree at all.
     *
     * The holes are shrunk as they move, by [DRIFT_MARGIN] of the distance travelled plus
     * a fixed pixel or two. The projection is an extrapolation, and an extrapolation that
     * is a few pixels out should err into the cover rather than into a strip of an
     * uncovered suggestion. Holes worn down to nothing are dropped.
     *
     * Returns null when the projection cannot be trusted — no scan to project from, or so
     * much scrolling has accumulated since one that the answer is a guess. The caller
     * then falls back to covering everything, which is always safe.
     */
    fun project(scan: FeedScan, dy: Int, drift: Int): List<Bounds>? {
        val feed = scan.feedBounds
        if (feed == null && scan.blackouts.isEmpty()) return null
        val span = feed?.height ?: scan.safe?.height ?: return null
        if (span <= 0 || drift > span * MAX_DRIFT) return null

        val margin = (drift * DRIFT_MARGIN).toInt() + MIN_BAND
        val holes = keptIn(scan).mapNotNull { hole ->
            val moved = Bounds(hole.left, hole.top - dy + margin, hole.right, hole.bottom - dy - margin)
            moved.takeIf { it.height >= MIN_HOLE }
        }
        val blackouts = scan.blackouts.mapNotNull { black ->
            val moved = Bounds(black.left, black.top - dy - margin, black.right, black.bottom - dy + margin)
            scan.safe?.let { clip(moved, it) } ?: moved
        }
        return blackouts.filterNot { it.isEmpty } + feedBands(feed, holes)
    }

    private fun clip(bounds: Bounds, region: Bounds): Bounds = Bounds(
        maxOf(bounds.left, region.left),
        maxOf(bounds.top, region.top),
        minOf(bounds.right, region.right),
        minOf(bounds.bottom, region.bottom),
    )

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
