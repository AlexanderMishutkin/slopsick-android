package dev.amishutkin.slopsick.core

/**
 * Reads Instagram's main feed.
 *
 * Instagram is the easy one. Despite being rendered by Litho, the feed exposes stable
 * view ids, so the decision that matters is an id lookup rather than a word match:
 *
 *   a post carries `inline_follow_button` in its header  ⇔  you do not follow the author
 *
 * That holds in every language, which the text signals would not. `secondary_label`
 * looks like the obvious signal and is not — it holds the audio track, "Edited · 7d",
 * "Translate with AI" *or* "Suggested for you", depending on the post.
 *
 * One structural wrinkle: a post is not a single node. It is a run of consecutive
 * children of the feed RecyclerView — header, then media, then the like/comment row —
 * so a post spans from its header to just before the next post's header.
 */
object InstagramAnalyzer {

    private const val FEED_LIST = "list"
    private const val HEADER = "row_feed_profile_header"
    private const val FOLLOW_BUTTON = "inline_follow_button"
    private const val PROFILE_NAME = "row_feed_photo_profile_name"
    private const val STORIES_TRAY = "reels_tray_container"
    private const val ACTION_BAR = "main_feed_action_bar"
    private const val TAB_BAR = "tab_bar"
    private const val REELS_TAB = "clips_tab"

    /**
     * The Reels player. Several ids identify it; this one wraps the whole surface and has
     * been stable across the captures taken so far.
     */
    private const val REELS_VIEWER = "clips_viewer_container"

    /** The search bar at the top of Explore; also the surest sign that this is Explore. */
    private const val EXPLORE_BAR = "explore_action_bar"
    private const val EXPLORE_GRID = "recycler_view"

    /**
     * Instagram has never shown an ad in any capture taken so far, so this string is the
     * one unverified signal in the file. It is also the one place a word match is
     * unavoidable: sponsored posts carry no distinguishing view id.
     */
    private val SPONSORED_LABELS = listOf("Sponsored", "Paid partnership")

    fun analyze(root: UiNode, settings: Settings = Settings()): FeedScan {
        if (!settings.instagram) return FeedScan.none(TargetApp.INSTAGRAM)
        // The Reels tab is dealt with on every Instagram screen, not just the feed:
        // Instagram opens straight into Reels often enough that only covering the button
        // when a feed happens to be on screen would miss the case that matters.
        val blockers = if (settings.hideReels) {
            listOfNotNull(root.findById(REELS_TAB)?.bounds?.takeIf { !it.isEmpty })
        } else {
            emptyList()
        }

        if (settings.hideReels) {
            val reels = root.findById(REELS_VIEWER)?.bounds
            if (reels != null && !reels.isEmpty) {
                return FeedScan(
                    app = TargetApp.INSTAGRAM,
                    feedBounds = null,
                    items = listOf(FeedItem(reels, Verdict.HIDE, Reason.REELS)),
                    surface = Surface.REELS,
                    blackouts = listOf(reels),
                    blockers = blockers,
                )
            }
        }

        // Explore is a grid of things the algorithm picked, top to bottom. The search bar
        // stays — searching is a thing you chose to do — and so does the tab bar.
        val exploreBar = root.findById(EXPLORE_BAR)
        if (settings.hideExplore && exploreBar != null) {
            val region = exploreRegion(root, exploreBar)
            if (region != null) {
                return FeedScan(
                    app = TargetApp.INSTAGRAM,
                    feedBounds = null,
                    items = listOf(FeedItem(region, Verdict.HIDE, Reason.EXPLORE)),
                    surface = Surface.EXPLORE,
                    blackouts = listOf(region),
                    blockers = blockers,
                )
            }
        }

        val list = findFeedList(root)
            ?: return FeedScan.none(TargetApp.INSTAGRAM).copy(blockers = blockers)
        val content = contentRegion(root, list)
        if (content.isEmpty) {
            return FeedScan.none(TargetApp.INSTAGRAM).copy(blockers = blockers)
        }

        val children = list.children
        val headerAt = children.indices.filter { children[it].containsId(HEADER) }

        val items = mutableListOf<FeedItem>()

        // Everything above the first header is the tail of a post whose header has
        // scrolled off the top, plus possibly the stories row. The tail is emitted as one
        // item rather than one per child: it is a single post, and splitting it would let
        // the ledger resolve half of it and cover the rest.
        val firstHeader = headerAt.firstOrNull() ?: children.size
        var pending: Bounds? = null
        for (i in 0 until firstHeader) {
            val child = children[i]
            if (child.bounds.isEmpty) continue
            if (child.containsId(STORIES_TRAY)) {
                pending?.let { items += FeedItem(it, Verdict.UNKNOWN, Reason.OFF_SCREEN_HEADER) }
                pending = null
                items += FeedItem(
                    bounds = child.bounds,
                    verdict = if (settings.hideStoriesTray) Verdict.HIDE else Verdict.KEEP,
                    reason = Reason.STORIES_TRAY,
                )
            } else {
                pending = pending?.union(child.bounds) ?: child.bounds
            }
        }
        pending?.let { items += FeedItem(it, Verdict.UNKNOWN, Reason.OFF_SCREEN_HEADER) }

        for ((n, start) in headerAt.withIndex()) {
            val end = headerAt.getOrNull(n + 1) ?: children.size
            val span = children.subList(start, end)
            val bounds = span.map { it.bounds }.filterNot { it.isEmpty }
                .reduceOrNull { a, b -> a.union(b) } ?: continue
            items += classify(span, bounds, settings)
        }

        return FeedScan(
            app = TargetApp.INSTAGRAM,
            feedBounds = content,
            items = items.mapNotNull { it.clipTo(content) },
            surface = Surface.FEED,
            blockers = blockers,
        )
    }

    private fun classify(span: List<UiNode>, bounds: Bounds, settings: Settings): FeedItem {
        val header = span.firstNotNullOfOrNull { it.findById(HEADER) }
        val author = header?.let { authorOf(it) }
        // "<author> posted a carousel 7 days ago" — stable for as long as the post is in
        // the feed, and distinct enough to tell two posts by the same author apart.
        val identity = header?.contentDesc?.trim()?.takeIf { it.isNotEmpty() }

        val sponsored = span.any { node ->
            node.labels().any { label -> SPONSORED_LABELS.any { label.startsWith(it) } }
        }
        if (sponsored) {
            return FeedItem(bounds, verdictFor(settings.hidePromoted), Reason.PROMOTED, author, identity)
        }

        val suggested = header?.containsId(FOLLOW_BUTTON) == true
        if (suggested) {
            return FeedItem(bounds, verdictFor(settings.hideSuggested), Reason.SUGGESTED, author, identity)
        }

        return FeedItem(bounds, Verdict.KEEP, Reason.FOLLOWED, author, identity)
    }

    private fun verdictFor(hide: Boolean) = if (hide) Verdict.HIDE else Verdict.KEEP

    private fun authorOf(header: UiNode): String? {
        header.findById(PROFILE_NAME)?.text?.replace('\u00a0', ' ')?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        // Falls back to the header's own description: "<author> posted a carousel 7 days ago".
        return header.contentDesc?.trim()?.substringBefore(" posted a ")
            ?.takeIf { it.isNotEmpty() && it != header.contentDesc?.trim() }
    }

    /** Everything between the search bar and the tab bar. */
    private fun exploreRegion(root: UiNode, bar: UiNode): Bounds? {
        val grid = root.findById(EXPLORE_GRID) ?: return null
        val bottom = root.findById(TAB_BAR)?.bounds?.top ?: grid.bounds.bottom
        val region = Bounds(grid.bounds.left, bar.bounds.bottom, grid.bounds.right, bottom)
        return region.takeIf { !it.isEmpty }
    }

    private fun findFeedList(root: UiNode): UiNode? =
        root.walk().firstOrNull {
            it.hasId(FEED_LIST) && it.className?.contains("RecyclerView") == true
        }

    /**
     * The feed list sits *behind* the floating toolbar and above the tab bar, so its own
     * bounds are not safe to draw over — covering them would black out Instagram's own
     * chrome and leave the user unable to navigate.
     */
    private fun contentRegion(root: UiNode, list: UiNode): Bounds {
        val top = root.findById(ACTION_BAR)?.bounds?.bottom ?: list.bounds.top
        val bottom = root.findById(TAB_BAR)?.bounds?.top ?: list.bounds.bottom
        return Bounds(
            left = list.bounds.left,
            top = maxOf(list.bounds.top, top),
            right = list.bounds.right,
            bottom = minOf(list.bounds.bottom, bottom),
        )
    }
}

/** Trims an item to the drawable feed region, or drops it when nothing is left. */
internal fun FeedItem.clipTo(region: Bounds): FeedItem? {
    val clipped = Bounds(
        left = maxOf(bounds.left, region.left),
        top = maxOf(bounds.top, region.top),
        right = minOf(bounds.right, region.right),
        bottom = minOf(bounds.bottom, region.bottom),
    )
    return if (clipped.isEmpty) null else copy(bounds = clipped)
}
