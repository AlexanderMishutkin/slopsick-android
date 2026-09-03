package dev.amishutkin.slopsick.core

/**
 * Reads instagram.com in Chrome.
 *
 * Chrome puts the whole page into the accessibility tree and its address bar alongside,
 * so both "which page is this" and "what is on it" are answerable. What it does not give
 * is structure: the page arrives as a deep pile of anonymous `View`s with no ids, and a
 * post is not a node — it is the stretch of page between one author's avatar and the
 * next.
 *
 * The covered region runs between the site's own two navigation bars. Those are page
 * content rather than app chrome, and covering them would leave the reader unable to go
 * anywhere. Everything between them is fair game, which matters more than it sounds: an
 * earlier version anchored the region to the first avatar it could see, and so uncovered
 * the top of the screen the moment a post scrolled far enough for its avatar to leave.
 */
object ChromeAnalyzer {

    private const val URL_BAR = "url_bar"
    private const val HOST = "instagram.com"

    /** "<name>'s profile picture" — the avatar that begins a post's header. */
    private val AVATAR = Regex("""^(.+)'s profile picture$""")

    private const val FOLLOW = "Follow"
    private const val SUGGESTED = "Suggested for you"
    private val SPONSORED = listOf("Sponsored", "Paid partnership")

    /** Names in the site's own navigation, used to find where the page's content stops. */
    private val NAV_ITEMS = setOf("Home", "Explore", "Reels", "Messages", "Search", "Instagram")

    /**
     * The stories row, which sits above the first post.
     *
     * Always kept here, and covered in the app. That reads like an inconsistency and was
     * asked for as one: in the app the toolbar and the stories row together are the top
     * quarter of the screen and the largest thing on it nobody chose, while on the web
     * the row is a strip. So `hideStoriesTray` does not reach this file.
     */
    private val STORY = Regex("""^(?:.* )?Your story$|^Story by .+""")

    /** Avatars closer together than this belong to the same header — a collaboration. */
    private const val SAME_HEADER = 140

    /** A navigation bar is a strip, not a panel; anything taller is page content. */
    private const val MAX_BAR_HEIGHT = 200

    /** What the address bar says this page is. */
    enum class Page { FEED, EXPLORE, REELS, OTHER }

    /**
     * The address bar's contents, or null when the bar is not on screen.
     *
     * Chrome hides its toolbar as soon as you scroll, so this answers only intermittently.
     * The caller remembers the last answer for as long as Chrome stays in front — asking
     * afresh every frame means the filter switches itself off the moment you start
     * reading.
     */
    fun url(root: UiNode): String? =
        root.findById(URL_BAR)?.text?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    fun pageOf(url: String?): Page {
        if (url == null || !url.contains(HOST)) return Page.OTHER
        val path = url.substringAfter(HOST).trimEnd('/')
        return when {
            path.startsWith("/explore") -> Page.EXPLORE
            path.startsWith("/reels") -> Page.REELS
            path.isEmpty() -> Page.FEED
            // A profile, a single post, the inbox. Not the feed, and not ours to cover.
            else -> Page.OTHER
        }
    }

    fun analyze(
        root: UiNode,
        settings: Settings = Settings(),
        page: Page = pageOf(url(root)),
    ): FeedScan {
        // The browser is only read for Instagram, so Instagram's own switch governs it.
        if (!settings.instagram || page == Page.OTHER) return FeedScan.none(TargetApp.CHROME)

        val web = root.walk().firstOrNull { it.className?.endsWith("WebView") == true }
            ?: return FeedScan.none(TargetApp.CHROME)

        // Chrome gives off-screen nodes zero height rather than dropping them, and the
        // WebView itself is labelled "Instagram", which would swallow the whole viewport.
        val labelled = web.walk()
            .filter { it !== web && it.bounds.height > 0 }
            .mapNotNull { node -> label(node)?.let { node to it } }
            .toList()

        // No navigation bars means this is not the logged-in shell — a login page, an
        // interstitial, a page still loading. Covering those helps nobody.
        val content = contentRegion(labelled, web) ?: return FeedScan.none(TargetApp.CHROME)
        if (content.isEmpty) return FeedScan.none(TargetApp.CHROME)

        return when (page) {
            Page.EXPLORE -> blanket(content, Reason.EXPLORE, Surface.EXPLORE, settings.hideExplore)
            Page.REELS -> blanket(content, Reason.REELS, Surface.REELS, settings.hideReels)
            else -> feed(content, labelled, settings)
        }
    }

    /** Explore and Reels are wall-to-wall algorithm; there is nothing to cut a hole for. */
    private fun blanket(
        content: Bounds,
        reason: Reason,
        surface: Surface,
        enabled: Boolean,
    ): FeedScan {
        if (!enabled) return FeedScan.none(TargetApp.CHROME)
        return FeedScan(
            app = TargetApp.CHROME,
            feedBounds = null,
            items = listOf(FeedItem(content, Verdict.HIDE, reason)),
            surface = surface,
            blackouts = listOf(content),
        )
    }

    private fun feed(
        content: Bounds,
        labelled: List<Pair<UiNode, String>>,
        settings: Settings,
    ): FeedScan {
        val heads = headerTops(labelled).filter { it in content.top until content.bottom }

        val items = mutableListOf<FeedItem>()

        val stories = storiesRow(labelled, content)
        if (stories != null) {
            items += FeedItem(stories, Verdict.KEEP, Reason.STORIES_TRAY)
        }

        // Above the first avatar is the tail of a post whose header has scrolled away.
        // Same as the native feed: covered, and left for the ledger to recognise.
        val fragmentTop = maxOf(content.top, stories?.bottom ?: content.top)
        val firstHead = heads.firstOrNull()
        if (firstHead == null || firstHead > fragmentTop + MIN_FRAGMENT) {
            val bottom = firstHead ?: content.bottom
            if (bottom > fragmentTop + MIN_FRAGMENT) {
                items += FeedItem(
                    Bounds(content.left, fragmentTop, content.right, bottom),
                    Verdict.UNKNOWN,
                    Reason.OFF_SCREEN_HEADER,
                )
            }
        }
        for ((index, top) in heads.withIndex()) {
            val bottom = heads.getOrNull(index + 1) ?: content.bottom
            classify(Bounds(content.left, top, content.right, bottom), labelled, settings)
                ?.let { items += it }
        }

        return FeedScan(
            app = TargetApp.CHROME,
            feedBounds = content,
            items = items.mapNotNull { it.clipTo(content) },
            surface = Surface.FEED,
        )
    }

    private fun classify(
        span: Bounds,
        labelled: List<Pair<UiNode, String>>,
        settings: Settings,
    ): FeedItem? {
        if (span.isEmpty) return null
        val inside = labelled
            .filter { (node, _) -> node.bounds.top >= span.top && node.bounds.top < span.bottom }
            .map { it.second }

        val author = inside.firstNotNullOfOrNull { AVATAR.find(it)?.groupValues?.get(1) }
        val identity = author?.let { "web:$it" }

        if (SPONSORED.any { s -> inside.any { it.startsWith(s) } }) {
            return FeedItem(span, hide(settings.hidePromoted), Reason.PROMOTED, author, identity)
        }
        if (inside.any { it == SUGGESTED || it == FOLLOW }) {
            return FeedItem(span, hide(settings.hideSuggested), Reason.SUGGESTED, author, identity)
        }
        return FeedItem(span, Verdict.KEEP, Reason.FOLLOWED, author, identity)
    }

    private fun hide(enabled: Boolean) = if (enabled) Verdict.HIDE else Verdict.KEEP

    /**
     * The page between its own two navigation bars — the header with the logo at the top,
     * and the Home/Explore/Reels strip at the bottom.
     */
    private fun contentRegion(labelled: List<Pair<UiNode, String>>, web: UiNode): Bounds? {
        val middle = (web.bounds.top + web.bounds.bottom) / 2
        val bars = labelled.filter { (node, text) ->
            text in NAV_ITEMS && node.bounds.height <= MAX_BAR_HEIGHT
        }
        val top = bars.filter { it.first.bounds.top < middle }
            .maxOfOrNull { it.first.bounds.bottom }
        val bottom = bars.filter { it.first.bounds.top > middle }
            .minOfOrNull { it.first.bounds.top }
        if (top == null && bottom == null) return null
        return Bounds(
            web.bounds.left,
            top ?: web.bounds.top,
            web.bounds.right,
            bottom ?: web.bounds.bottom,
        )
    }

    /** The row of story bubbles above the feed, if any of it is on screen. */
    private fun storiesRow(labelled: List<Pair<UiNode, String>>, content: Bounds): Bounds? =
        labelled
            .filter { (node, text) -> STORY.matches(text) && node.bounds.top >= content.top }
            .map { it.first.bounds }
            .reduceOrNull { a, b -> a.union(b) }
            ?.let { Bounds(content.left, it.top, content.right, it.bottom) }
            ?.takeIf { !it.isEmpty && it.bottom <= content.bottom }

    private fun headerTops(labelled: List<Pair<UiNode, String>>): List<Int> {
        val tops = labelled
            .filter { (_, text) -> AVATAR.matches(text) }
            .map { it.first.bounds.top }
            .sorted()

        val heads = mutableListOf<Int>()
        for (top in tops) {
            if (heads.isEmpty() || top - heads.last() > SAME_HEADER) heads += top
        }
        return heads
    }

    private fun label(node: UiNode): String? =
        (node.contentDesc ?: node.text)?.replace(' ', ' ')?.trim()?.takeIf { it.isNotEmpty() }

    /** Below this, the gap above the first post is not worth an item of its own. */
    private const val MIN_FRAGMENT = 8
}
