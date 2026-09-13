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

    /**
     * "You have seen all new posts" — the line Instagram draws where the feed you chose
     * ends and its recommendations begin. Kept, on request: it is the one thing on the
     * screen that says the filter is working rather than broken, and covering it made a
     * working filter look like a feed that had simply gone blank. Only the bar itself is
     * kept; the "Suggested for you" heading below it is still the algorithm talking.
     */
    private const val DEMARCATOR = "demarcator_bar_container"
    private const val ACTION_BAR = "main_feed_action_bar"
    private const val TAB_BAR = "tab_bar"
    private const val REELS_TAB = "clips_tab"

    /**
     * The Reels player. Several ids identify it; this one wraps the whole surface and has
     * been stable across the captures taken so far.
     */
    private const val REELS_VIEWER = "clips_viewer_container"

    /**
     * A profile screen's own chrome. This matters more than it looks: a profile's post
     * grid is a `RecyclerView` carrying the id `list` — the same id the home feed's list
     * carries — so "find the feed" found it, no post headers existed in a grid of
     * thumbnails, and the whole of somebody's profile went under one cover. Three bug
     * reports off the phone were this, and none of the 35 captured feeds carries any of
     * these ids.
     */
    private val PROFILE_CHROME = listOf("profile_action_bar", "profile_header_container")

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
        // Everything this analyzer returns is clamped to the region between Instagram's
        // own bars, so that no failure to recognise a screen can end with the tab bar
        // painted out and no way back to the feed.
        val chrome = ScreenChrome.of(root, topBarId = ACTION_BAR, navBarId = TAB_BAR)
        // The Reels tab is dealt with on every Instagram screen, not just the feed:
        // Instagram opens straight into Reels often enough that only covering the button
        // when a feed happens to be on screen would miss the case that matters.
        val blockers = if (settings.hideReels) {
            listOfNotNull(root.findById(REELS_TAB)?.bounds?.takeIf { !it.isEmpty })
        } else {
            emptyList()
        }

        if (settings.hideReels) {
            // The player runs edge to edge, under the tab bar included. Covering it as
            // reported would hide the only way out of Reels.
            val reels = root.findById(REELS_VIEWER)?.bounds?.let { chrome.clamp(it) }
            if (reels != null) {
                return FeedScan(
                    app = TargetApp.INSTAGRAM,
                    feedBounds = null,
                    items = listOf(FeedItem(reels, Verdict.HIDE, Reason.REELS)),
                    surface = Surface.REELS,
                    blackouts = listOf(reels),
                    blockers = blockers,
                    safe = chrome.safe,
                    navBar = chrome.navBar,
                    barless = chrome.barless,
                )
            }
        }

        // A profile is somewhere you navigated on purpose, and nothing on it was chosen
        // for you. Checked before Explore because a profile parked in a pager can carry
        // Explore's own ids off-screen beside it.
        if (isProfile(root)) {
            return FeedScan.none(TargetApp.INSTAGRAM).copy(blockers = blockers)
        }

        // Explore is a grid of things the algorithm picked, top to bottom. The search bar
        // stays — searching is a thing you chose to do — and so does the tab bar.
        val exploreBar = root.findById(EXPLORE_BAR)
        if (settings.hideExplore && exploreBar != null) {
            val region = exploreRegion(root, exploreBar, chrome)
            if (region != null) {
                return FeedScan(
                    app = TargetApp.INSTAGRAM,
                    feedBounds = null,
                    items = listOf(FeedItem(region, Verdict.HIDE, Reason.EXPLORE)),
                    surface = Surface.EXPLORE,
                    blackouts = listOf(region),
                    blockers = blockers,
                    safe = chrome.safe,
                    navBar = chrome.navBar,
                    barless = chrome.barless,
                )
            }
        }

        val list = findFeedList(root)
            ?: return FeedScan.none(TargetApp.INSTAGRAM).copy(blockers = blockers)
        val content = contentRegion(list, chrome)
        if (content.isEmpty) {
            return FeedScan.none(TargetApp.INSTAGRAM).copy(blockers = blockers)
        }

        val children = list.children
        val headerAt = children.indices.filter { children[it].containsId(HEADER) }
        // The caught-up line divides the feed as firmly as a post header does, and can
        // turn up on either side of one, so it breaks the grouping the same way.
        val demarcatorAt = children.indices.filter { children[it].containsId(DEMARCATOR) }
        val breaks = (headerAt + demarcatorAt).distinct().sorted()

        val items = mutableListOf<FeedItem>()

        // Everything above the first break is the tail of a post whose header has
        // scrolled off the top, plus possibly the stories row. The tail is emitted as one
        // item rather than one per child: it is a single post, and splitting it would let
        // the ledger resolve half of it and cover the rest.
        var pending: Bounds? = null
        for (i in 0 until (breaks.firstOrNull() ?: children.size)) {
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

        for ((n, start) in breaks.withIndex()) {
            val end = breaks.getOrNull(n + 1) ?: children.size
            val span = children.subList(start, end)
            val caughtUp = caughtUpLine(children[start])
            if (caughtUp != null) {
                items += FeedItem(caughtUp, Verdict.KEEP, Reason.FEED_MODULE)
                continue
            }
            val bounds = span.map { it.bounds }.filterNot { it.isEmpty }
                .reduceOrNull { a, b -> a.union(b) } ?: continue
            items += classify(span, bounds, content, settings)
        }

        return FeedScan(
            app = TargetApp.INSTAGRAM,
            feedBounds = content,
            items = items.mapNotNull { it.clipTo(content) },
            surface = Surface.FEED,
            blockers = blockers,
            safe = chrome.safe,
            navBar = chrome.navBar,
            barless = chrome.barless,
        )
    }

    private fun classify(
        span: List<UiNode>,
        bounds: Bounds,
        content: Bounds,
        settings: Settings,
    ): FeedItem {
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

        // The whole Instagram rule is that a *missing* follow button means you follow the
        // author. That only holds while the header is fully on screen. Scroll one halfway
        // off and Instagram stops reporting the button — the header's own bounds come back
        // inverted, bottom above top — so a suggestion reads as a post from a friend and
        // is uncovered. A phone report caught exactly that: the header still said
        // "Suggested for you" in its secondary label while the post was kept.
        //
        // Nothing can be concluded from a clipped header, so nothing is: the item keeps
        // its identity and goes back UNKNOWN, which covers it, and the ledger uncovers it
        // again if it saw this same post whole a moment ago.
        if (header != null && clipped(header.bounds, content)) {
            return FeedItem(bounds, Verdict.UNKNOWN, Reason.OFF_SCREEN_HEADER, author, identity)
        }

        return FeedItem(bounds, Verdict.KEEP, Reason.FOLLOWED, author, identity)
    }

    private fun verdictFor(hide: Boolean) = if (hide) Verdict.HIDE else Verdict.KEEP

    /** Whether a header is cut off at the top of the feed, or reports impossible bounds. */
    private fun clipped(header: Bounds, content: Bounds): Boolean =
        header.isEmpty || header.top < content.top

    /**
     * The caught-up line inside [child], from the top of its card down to the bottom of
     * the bar. Taking the card's top rather than the bar's own leaves no stripe of
     * padding to paint above it, which at that height would be pure noise.
     */
    private fun caughtUpLine(child: UiNode): Bounds? {
        val bar = child.findById(DEMARCATOR)?.bounds?.takeIf { !it.isEmpty } ?: return null
        return Bounds(child.bounds.left, child.bounds.top, child.bounds.right, bar.bottom)
    }

    private fun authorOf(header: UiNode): String? {
        header.findById(PROFILE_NAME)?.text?.replace('\u00a0', ' ')?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        // Falls back to the header's own description: "<author> posted a carousel 7 days ago".
        return header.contentDesc?.trim()?.substringBefore(" posted a ")
            ?.takeIf { it.isNotEmpty() && it != header.contentDesc?.trim() }
    }

    /** Everything between the search bar and the tab bar. */
    private fun exploreRegion(root: UiNode, bar: UiNode, chrome: ScreenChrome): Bounds? {
        val grid = root.findById(EXPLORE_GRID) ?: return null
        val region = Bounds(
            grid.bounds.left,
            bar.bounds.bottom,
            grid.bounds.right,
            grid.bounds.bottom,
        )
        return chrome.clamp(region)
    }

    /**
     * Whether a profile screen is on display. Bounds are checked, not just presence:
     * Instagram parks whole screens off to the side of the visible one, and an id with
     * impossible bounds is a screen you are not looking at.
     */
    private fun isProfile(root: UiNode): Boolean =
        root.walk().any { node -> PROFILE_CHROME.any { node.hasId(it) } && !node.bounds.isEmpty }

    private fun findFeedList(root: UiNode): UiNode? =
        root.walk().firstOrNull {
            it.hasId(FEED_LIST) && it.className?.contains("RecyclerView") == true
        }

    /**
     * The feed list sits *behind* the floating toolbar and above the tab bar, so its own
     * bounds are not safe to draw over — covering them would black out Instagram's own
     * chrome and leave the user unable to navigate.
     *
     * Instagram hides the toolbar as soon as you scroll, so on most frames there is no
     * top bar to sit under and the feed's own top is the right ceiling. The floor is
     * never the feed's own bottom: see [ScreenChrome].
     */
    private fun contentRegion(list: UiNode, chrome: ScreenChrome): Bounds = Bounds(
        left = list.bounds.left,
        top = maxOf(list.bounds.top, chrome.ceiling),
        right = list.bounds.right,
        bottom = minOf(list.bounds.bottom, chrome.floor),
    )
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
