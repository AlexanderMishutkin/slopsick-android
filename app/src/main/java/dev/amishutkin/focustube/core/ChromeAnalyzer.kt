package dev.amishutkin.focustube.core

/**
 * Reads Instagram's mobile web feed inside Chrome.
 *
 * Chrome puts the whole page into the accessibility tree and its address bar alongside,
 * so both "which site is this" and "what is on it" are answerable. What it does not give
 * is any structure: the page arrives as a deep pile of anonymous `View`s with no ids, and
 * a post is not a node — it is a stretch of the page between one author's avatar and the
 * next one's.
 *
 * That shapes two decisions:
 *
 *  - Posts are delimited by their author avatars, the same way the native feed is
 *    delimited by its post headers.
 *  - The covered region runs from the first avatar to the last post, and no further.
 *    Everything above it (the site's own header, the stories row) and below it (the
 *    bottom navigation) is left alone. Covering the whole viewport the way the native
 *    analyzers do would black out the site's navigation and strand the reader.
 *
 * Every signal here is a word. Chrome exposes the page's accessible names, which are the
 * same strings a screen reader would announce, and they are translated with the site.
 */
object ChromeAnalyzer {

    private const val URL_BAR = "url_bar"
    private const val INSTAGRAM_HOST = "instagram.com"

    /** "<name>'s profile picture" — the avatar that begins a post's header. */
    private val AVATAR = Regex("""^(.+)'s profile picture$""")

    private const val FOLLOW = "Follow"
    private const val SUGGESTED = "Suggested for you"
    private val SPONSORED = listOf("Sponsored", "Paid partnership")

    /** Names in the site's own bottom navigation, used to find where the feed stops. */
    private val NAV_ITEMS = setOf("Home", "Explore", "Reels", "Messages", "Search")

    /** Avatars closer together than this belong to the same header — a collaboration. */
    private const val SAME_HEADER = 140

    /**
     * The host in the address bar, or null when the bar is not on screen.
     *
     * Chrome hides its toolbar as soon as you scroll down, so this answers "which site is
     * this" only intermittently. The caller is expected to remember the last answer for
     * as long as Chrome stays in front — checking afresh every frame means the filter
     * switches itself off the moment you start reading.
     */
    fun host(root: UiNode): String? =
        root.findById(URL_BAR)?.text?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    fun isInstagram(host: String?): Boolean = host?.contains(INSTAGRAM_HOST) == true

    fun analyze(
        root: UiNode,
        settings: Settings = Settings(),
        onInstagram: Boolean = isInstagram(host(root)),
    ): FeedScan {
        if (!onInstagram) return FeedScan.none(TargetApp.CHROME)

        val page = root.walk().firstOrNull { it.className?.endsWith("WebView") == true }
            ?: return FeedScan.none(TargetApp.CHROME)

        // Chrome gives off-screen nodes zero height rather than dropping them.
        val labelled = page.walk()
            .filter { it.bounds.height > 0 }
            .mapNotNull { node -> label(node)?.let { node to it } }
            .toList()

        val anchors = headerTops(labelled)
        if (anchors.isEmpty()) return FeedScan.none(TargetApp.CHROME)

        val floor = navigationTop(labelled, page) ?: page.bounds.bottom
        val feed = Bounds(page.bounds.left, anchors.first(), page.bounds.right, floor)
        if (feed.isEmpty) return FeedScan.none(TargetApp.CHROME)

        val items = anchors.mapIndexed { index, top ->
            val bottom = anchors.getOrNull(index + 1) ?: floor
            val span = Bounds(feed.left, top, feed.right, minOf(bottom, floor))
            classify(span, labelled, settings)
        }.mapNotNull { it?.clipTo(feed) }

        return FeedScan(TargetApp.CHROME, feed, items, Surface.FEED)
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
        val identity = author?.let { "web:$it@${span.top}" }

        if (SPONSORED.any { s -> inside.any { it.startsWith(s) } }) {
            return FeedItem(span, hide(settings.hidePromoted), Reason.PROMOTED, author, identity)
        }
        if (inside.any { it == SUGGESTED } || inside.any { it == FOLLOW }) {
            return FeedItem(span, hide(settings.hideSuggested), Reason.SUGGESTED, author, identity)
        }
        return FeedItem(span, Verdict.KEEP, Reason.FOLLOWED, author, identity)
    }

    private fun hide(enabled: Boolean) = if (enabled) Verdict.HIDE else Verdict.KEEP

    /**
     * The top of each post. A post can carry several avatars when it is a collaboration,
     * so avatars within a header's height of each other count once.
     */
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

    /** Where the site's own bottom navigation starts, so the feed can stop above it. */
    private fun navigationTop(labelled: List<Pair<UiNode, String>>, page: UiNode): Int? {
        val middle = (page.bounds.top + page.bounds.bottom) / 2
        return labelled
            .filter { (node, text) -> text in NAV_ITEMS && node.bounds.top > middle }
            .minOfOrNull { it.first.bounds.top }
    }

    private fun label(node: UiNode): String? =
        (node.contentDesc ?: node.text)?.replace(' ', ' ')?.trim()?.takeIf { it.isNotEmpty() }
}
