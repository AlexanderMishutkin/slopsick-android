package dev.amishutkin.slopsick.core

/**
 * Reads YouTube.
 *
 * Only Shorts is covered here. The home feed is left alone — the ads in it are not worth
 * the false positives, and were explicitly not asked for.
 *
 * YouTube's feed rows carry no view ids at all (only the RecyclerView holding them does),
 * so a shelf has to be recognised from its shape. The first version matched the strings
 * instead — the heading "Shorts", and the `- play Short` suffix each thumbnail carries in
 * its description — and on a device whose YouTube build does not write that suffix the
 * result was the worst possible one: the heading was covered and the videos underneath
 * it played on.
 *
 * The shape is the reliable signal, and it is the one thing every Shorts shelf has in
 * common with no ordinary row: **YouTube's feed is one video per row, and Shorts come
 * two or three abreast, in portrait.** A row holding several tall tiles side by side is
 * a Shorts shelf in any locale, on any build, whatever the descriptions say. The string
 * matches are kept as a second opinion — they catch the heading row, which has no tiles
 * in it — but nothing depends on them any more.
 */
object YouTubeAnalyzer {

    private const val NAV_BAR = "pivot_bar"
    private const val NAV_LABEL = "text"
    private const val FEED = "results"

    /** The Shorts player, which fills the screen above the navigation bar. */
    private const val SHORTS_PLAYER = "reel_watch_fragment_root"

    /**
     * YouTube leaves "Shorts" untranslated in most locales, which is the only reason
     * matching on it is viable at all.
     */
    private const val SHORTS = "Shorts"

    /**
     * The suffix a Shorts thumbnail carries in its description on the builds that write
     * one: "<title>, <channel>, 4 days ago - play Short".
     */
    private const val SHORTS_ITEM = "play Short"

    /** Long enough to be a video title rather than a shelf heading. */
    private const val MAX_HEADING = 24

    // --- the shape of a Shorts shelf ---------------------------------------------

    /** A tile has to be this much taller than it is wide. Shorts are 9:16. */
    private const val MIN_ASPECT = 1.3

    /** And this tall relative to the screen, so that a row of chips cannot qualify. */
    private const val MIN_TILE_HEIGHT = 0.18

    /** No tile may fill the row: that is what an ordinary one-per-row video looks like. */
    private const val MAX_TILE_WIDTH = 0.6

    /** Two abreast is the narrowest shelf YouTube draws. */
    private const val MIN_TILES = 2

    /** Together the tiles have to account for most of the row's width. */
    private const val MIN_COVERAGE = 0.6

    fun analyze(root: UiNode, settings: Settings = Settings()): FeedScan {
        if (!settings.youtube) return FeedScan.none(TargetApp.YOUTUBE)
        if (!settings.hideShorts) return FeedScan.none(TargetApp.YOUTUBE)

        val chrome = ScreenChrome.of(root, navBarId = NAV_BAR)
        val blockers = listOfNotNull(shortsTab(root)?.takeIf { !it.isEmpty })

        // The player runs edge to edge and the feed rows below run under the navigation
        // bar, so everything painted is clamped to the region between YouTube's own bars.
        // Without that, leaving Shorts means finding a tab bar that has been painted out.
        val player = root.findById(SHORTS_PLAYER)?.bounds?.let { chrome.clamp(it) }
        if (player != null) {
            return FeedScan(
                app = TargetApp.YOUTUBE,
                feedBounds = null,
                items = listOf(FeedItem(player, Verdict.HIDE, Reason.REELS)),
                surface = Surface.REELS,
                blackouts = listOf(player),
                blockers = blockers,
                safe = chrome.safe,
                navBar = chrome.navBar,
                barless = chrome.barless,
            )
        }

        val shelves = shortsShelves(root, chrome)
        return FeedScan(
            app = TargetApp.YOUTUBE,
            feedBounds = null,
            items = shelves.map { FeedItem(it, Verdict.HIDE, Reason.SHORTS_SHELF) },
            surface = Surface.FEED,
            blackouts = shelves,
            blockers = blockers,
            safe = chrome.safe,
            navBar = chrome.navBar,
            barless = chrome.barless,
        )
    }

    /**
     * The Shorts shelves in the feed, each as one region.
     *
     * A shelf is not one row. YouTube puts the heading in its own child of the feed and
     * the videos in the next one or two, so the run has to be stitched back together —
     * otherwise the caption is covered and the videos play on underneath, which is
     * exactly what happened before the shape test was added.
     */
    private fun shortsShelves(root: UiNode, chrome: ScreenChrome): List<Bounds> {
        val feed = feedContainer(root, chrome) ?: return emptyList()
        val rows = feed.children.filterNot { it.bounds.isEmpty }

        val shelves = mutableListOf<Bounds>()
        var run: Bounds? = null
        for (row in rows) {
            if (isShelfRow(row, chrome.screen)) {
                run = run?.union(row.bounds) ?: row.bounds
            } else {
                run?.let { shelves += it }
                run = null
            }
        }
        run?.let { shelves += it }
        return shelves.mapNotNull { chrome.clamp(it) }
    }

    /**
     * The list the feed rows are children of.
     *
     * `results` is the id YouTube has used for it in every capture taken, but the whole
     * point of this rewrite is not to depend on a name, so when it is missing the tallest
     * scrolling view on screen stands in.
     */
    private fun feedContainer(root: UiNode, chrome: ScreenChrome): UiNode? {
        root.findById(FEED)?.let { return it }
        return root.walk()
            .filter { it.className?.contains("RecyclerView") == true }
            .filter { it.bounds.height >= chrome.screen.height / 2 && it.children.size >= 2 }
            .maxByOrNull { it.bounds.height }
    }

    private fun isShelfRow(row: UiNode, screen: Bounds): Boolean {
        if (tiles(row, screen).size >= MIN_TILES) return true
        val labels = row.labels()
        return labels.any { it.contains(SHORTS_ITEM) } ||
            labels.any { it.length <= MAX_HEADING && it == SHORTS }
    }

    /**
     * The portrait thumbnails sitting side by side in this row, if there are any.
     *
     * Each column of a shelf is a stack of nested views with much the same bounds, so
     * only the outermost of each nest is counted — otherwise one column looks like five
     * tiles and every row in the feed becomes a shelf.
     */
    private fun tiles(row: UiNode, screen: Bounds): List<Bounds> {
        if (screen.isEmpty || row.bounds.isEmpty) return emptyList()
        val candidates = row.walk()
            .map { it.bounds }
            .filter { b ->
                !b.isEmpty &&
                    b.height >= b.width * MIN_ASPECT &&
                    b.height >= screen.height * MIN_TILE_HEIGHT &&
                    b.width <= row.bounds.width * MAX_TILE_WIDTH
            }
            .distinct()
            .toList()
        if (candidates.size < MIN_TILES) return emptyList()

        val outer = candidates.filter { inner ->
            candidates.none { it != inner && contains(it, inner) }
        }
        if (outer.size < MIN_TILES) return emptyList()

        val ordered = outer.sortedBy { it.left }
        for (i in 1 until ordered.size) {
            // Overlapping columns are one thing drawn in layers, not a row of tiles.
            if (ordered[i].left < ordered[i - 1].right) return emptyList()
        }
        if (ordered.sumOf { it.width } < row.bounds.width * MIN_COVERAGE) return emptyList()
        return ordered
    }

    private fun contains(outer: Bounds, inner: Bounds): Boolean =
        outer.left <= inner.left && outer.top <= inner.top &&
            outer.right >= inner.right && outer.bottom >= inner.bottom

    /** The Shorts entry in the bottom navigation, if it is on screen. */
    private fun shortsTab(root: UiNode): Bounds? {
        val bar = root.findById(NAV_BAR) ?: return null
        val label = bar.walk().firstOrNull { node ->
            node.contentDesc?.trim() == SHORTS ||
                (node.hasId(NAV_LABEL) && node.text?.trim() == SHORTS)
        } ?: return null

        // The label may be the button's caption rather than the button, and covering the
        // caption alone would leave the icon above it showing.
        val button = bar.walk().filter { candidate ->
            candidate.bounds.left <= label.bounds.left &&
                candidate.bounds.right >= label.bounds.right &&
                candidate.bounds.top <= label.bounds.top &&
                candidate.bounds.bottom >= label.bounds.bottom &&
                candidate.bounds.width < bar.bounds.width
        }.maxByOrNull { it.bounds.height }

        return button?.bounds ?: label.bounds
    }
}
