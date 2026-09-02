package dev.amishutkin.slopsick.core

/**
 * Reads YouTube.
 *
 * Only Shorts is covered here. The home feed is left alone — the ads in it are not worth
 * the false positives, and were explicitly not asked for.
 *
 * YouTube's feed rows carry no view ids at all (only the RecyclerView holding them does),
 * so a Shorts shelf can only be recognised by its heading. That is a word match, and it
 * is the weakest thing in this file. See the README: no Shorts shelf has ever appeared on
 * the account this was developed against, so unlike everything else here, this rule has
 * never been seen to fire.
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
     * A Shorts item describes itself: "<title>, <channel>, 4 days ago - play Short". The
     * heading row and the grid rows beneath it are separate children of the feed, so
     * matching the heading alone covers a caption and leaves the videos playing.
     */
    private const val SHORTS_ITEM = "play Short"

    /** Long enough to be a video title rather than a shelf heading. */
    private const val MAX_HEADING = 24

    fun analyze(root: UiNode, settings: Settings = Settings()): FeedScan {
        if (!settings.youtube) return FeedScan.none(TargetApp.YOUTUBE)
        if (!settings.hideShorts) return FeedScan.none(TargetApp.YOUTUBE)

        val blockers = listOfNotNull(shortsTab(root)?.takeIf { !it.isEmpty })

        val player = root.findById(SHORTS_PLAYER)?.bounds
        if (player != null && !player.isEmpty) {
            return FeedScan(
                app = TargetApp.YOUTUBE,
                feedBounds = null,
                items = listOf(FeedItem(player, Verdict.HIDE, Reason.REELS)),
                surface = Surface.REELS,
                blackouts = listOf(player),
                blockers = blockers,
            )
        }

        val shelves = shortsShelves(root)
        return FeedScan(
            app = TargetApp.YOUTUBE,
            feedBounds = null,
            items = shelves.map { FeedItem(it, Verdict.HIDE, Reason.SHORTS_SHELF) },
            surface = Surface.FEED,
            blackouts = shelves,
            blockers = blockers,
        )
    }

    /**
     * The Shorts shelves in the home feed, each as one region.
     *
     * A shelf is not one row. YouTube puts the "Shorts" heading in its own child of the
     * feed and the videos in the next one or two, so the run has to be stitched back
     * together — otherwise the caption is covered and the videos play on underneath.
     */
    private fun shortsShelves(root: UiNode): List<Bounds> {
        val feed = root.findById(FEED) ?: return emptyList()
        val rows = feed.children.filter { !it.bounds.isEmpty }

        val shelves = mutableListOf<Bounds>()
        var run: Bounds? = null
        for (row in rows) {
            if (isShelfRow(row)) {
                run = run?.union(row.bounds) ?: row.bounds
            } else {
                run?.let { shelves += it }
                run = null
            }
        }
        run?.let { shelves += it }
        return shelves
    }

    private fun isShelfRow(row: UiNode): Boolean {
        val labels = row.labels()
        val heading = labels.any { it.length <= MAX_HEADING && it == SHORTS }
        val items = labels.any { it.contains(SHORTS_ITEM) }
        return heading || items
    }

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
