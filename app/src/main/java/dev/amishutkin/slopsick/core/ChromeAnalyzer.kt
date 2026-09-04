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
 *
 * ## Why so little of this is a string comparison
 *
 * The native analyzers read view ids, which Instagram ships in English whatever language
 * the account is set to. The web has no ids, only the labels a screen reader would read
 * out — and those are translated. A capture from a phone whose Instagram is in Russian
 * matched not one landmark: no avatar was `"X's profile picture"`, it was
 * `"Фото профиля X"`, and with no avatars there were no post boundaries, so the whole
 * feed collapsed into a single unrecognised block and got covered end to end — friends'
 * posts included. That is the failure this file is now built to survive.
 *
 * So every landmark here is found by shape first, with the words as a second opinion:
 * the site's two bars are rows of equal-height controls flush with an edge, the stories
 * tray is a row of equal-height tiles above the first post, and a post header is a small
 * square image at the left margin with a name beside it. Shape is what all the
 * translations have in common. Where words are still used they are listed per language
 * and treated as a bonus, never as the only way through.
 */
object ChromeAnalyzer {

    private const val URL_BAR = "url_bar"
    private const val HOST = "instagram.com"

    /**
     * The avatar that begins a post's header, as each language spells it. Matching one of
     * these gives the author's name for free, which is worth having — but the structural
     * rule below finds the same avatars without knowing any language at all.
     */
    private val AVATAR = listOf(
        Regex("""^(.+)'s profile picture$"""),
        Regex("""^Profile picture of (.+)$"""),
        Regex("""^Фото профиля (.+)$"""),
    )

    private val FOLLOW = setOf("Follow", "Подписаться")
    private val SUGGESTED = setOf("Suggested for you", "Рекомендуемые публикации", "Рекомендации для вас")
    private val SPONSORED = listOf("Sponsored", "Paid partnership", "Реклама")

    /** Names in the site's own navigation, used when its shape is not conclusive. */
    private val NAV_ITEMS = setOf(
        "Home", "Explore", "Reels", "Messages", "Search", "Instagram",
        "Главная", "Интересное", "Сообщения", "Поиск",
    )

    /**
     * The stories row, which sits above the first post. Kept, exactly as in the app — an
     * earlier version swept it into the "everything above the first post" fragment and
     * covered it, which the native side never did.
     */
    private val STORY = listOf(
        Regex("""^(?:.* )?Your story$"""),
        Regex("""^Story by .+"""),
        Regex("""^(?:.* )?Ваша история$"""),
        Regex("""^История .+"""),
    )

    /** Avatars closer together than this belong to the same header — a collaboration. */
    private const val SAME_HEADER = 140

    // --- Shapes, all as a fraction of the page's own size rather than in pixels. ---

    /** A navigation bar is a strip; the stories tray, the next tallest thing, is not. */
    private const val MAX_BAR = 0.11

    /** A bar hugs its edge of the page, and spans nearly all of its width. */
    private const val BAR_FLUSH = 0.06
    private const val BAR_ZONE = 0.20
    private const val MIN_BAR_WIDTH = 0.70
    private const val MIN_BAR_ITEMS = 3

    /** A node this close to the top of the page is part of the header, not content. */
    private const val TOP_FLUSH = 8

    /** The stories tray: a row of equal tiles, taller than a bar, near the feed's head. */
    private const val MIN_STORY_HEIGHT = 0.10
    private const val MAX_STORY_HEIGHT = 0.35
    private const val MIN_STORY_WIDTH = 0.50
    private const val MIN_STORY_TILES = 3
    private const val STORY_ZONE = 0.40

    /** A header avatar: a small square at the left margin, with a name beside it. */
    private const val MIN_AVATAR = 0.055
    private const val MAX_AVATAR = 0.14
    private const val AVATAR_MARGIN = 0.10
    private const val SQUARE = 0.25
    private const val MAX_AVATAR_SIBLINGS = 2

    /** A Follow button: wide, on the right of the header row. */
    private const val MIN_FOLLOW_WIDTH = 0.15
    private const val FOLLOW_SIDE = 0.45

    /** Below this, the gap above the first post is a margin, not a scrolled-past post. */
    private const val MIN_FRAGMENT = 0.04

    /** What the address bar says this page is. */
    enum class Page { FEED, EXPLORE, REELS, OTHER }

    /** A labelled node, with how many siblings it was found among. */
    private class Tag(val node: UiNode, val text: String, val siblings: Int) {
        val bounds: Bounds get() = node.bounds
    }

    /** One post header: the avatar that starts it, and the author if we could read one. */
    private class Header(val avatar: Bounds, val name: String?)

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

        val tags = tags(web)

        // No navigation bars means this is not the logged-in shell — a login page, an
        // interstitial, a page still loading. Covering those helps nobody.
        val content = contentRegion(tags, web) ?: return FeedScan.none(TargetApp.CHROME)
        if (content.isEmpty) return FeedScan.none(TargetApp.CHROME)

        return when (page) {
            Page.EXPLORE -> blanket(content, Reason.EXPLORE, Surface.EXPLORE, settings.hideExplore)
            Page.REELS -> blanket(content, Reason.REELS, Surface.REELS, settings.hideReels)
            else -> feed(content, tags, settings)
        }
    }

    /**
     * Every labelled node under the WebView, with its sibling count.
     *
     * Chrome gives off-screen nodes zero height rather than dropping them, and the
     * WebView itself is labelled "Instagram", which would swallow the whole viewport.
     * The sibling count is what separates a post's avatar — alone in its own wrapper —
     * from the like button, which is one of eight controls in a row and just as square.
     */
    private fun tags(web: UiNode): List<Tag> {
        val out = mutableListOf<Tag>()
        fun visit(node: UiNode) {
            val kids = node.children
            for (kid in kids) {
                if (kid.bounds.height > 0) {
                    label(kid)?.let { out += Tag(kid, it, kids.size) }
                }
                visit(kid)
            }
        }
        visit(web)
        return out
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

    private fun feed(content: Bounds, tags: List<Tag>, settings: Settings): FeedScan {
        val heads = headers(tags, content)
        val items = mutableListOf<FeedItem>()

        val stories = storiesRow(tags, content, heads.firstOrNull()?.avatar?.top)
        if (stories != null) {
            items += FeedItem(
                stories,
                if (settings.hideStoriesTray) Verdict.HIDE else Verdict.KEEP,
                Reason.STORIES_TRAY,
            )
        }

        // Above the first avatar is the tail of a post whose header has scrolled away.
        // Same as the native feed: covered, and left for the ledger to recognise.
        val fragmentTop = maxOf(content.top, stories?.bottom ?: content.top)
        val margin = (content.height * MIN_FRAGMENT).toInt()
        val firstHead = heads.firstOrNull()?.avatar?.top
        if (firstHead == null || firstHead > fragmentTop + margin) {
            val bottom = firstHead ?: content.bottom
            if (bottom > fragmentTop + margin) {
                items += FeedItem(
                    Bounds(content.left, fragmentTop, content.right, bottom),
                    Verdict.UNKNOWN,
                    Reason.OFF_SCREEN_HEADER,
                )
            }
        }
        for ((index, head) in heads.withIndex()) {
            val bottom = heads.getOrNull(index + 1)?.avatar?.top ?: content.bottom
            val span = Bounds(content.left, head.avatar.top, content.right, bottom)
            classify(span, head, tags, content, settings)?.let { items += it }
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
        head: Header,
        tags: List<Tag>,
        content: Bounds,
        settings: Settings,
    ): FeedItem? {
        if (span.isEmpty) return null
        val inside = tags
            .filter { it.bounds.top >= span.top && it.bounds.top < span.bottom }
            .map { it.text }

        val author = head.name
        val identity = author?.let { "web:$it" }

        if (SPONSORED.any { s -> inside.any { it.startsWith(s) } }) {
            return FeedItem(span, hide(settings.hidePromoted), Reason.PROMOTED, author, identity)
        }
        if (inside.any { it in SUGGESTED || it in FOLLOW } || hasFollowButton(head, tags, content)) {
            return FeedItem(span, hide(settings.hideSuggested), Reason.SUGGESTED, author, identity)
        }
        return FeedItem(span, Verdict.KEEP, Reason.FOLLOWED, author, identity)
    }

    /**
     * A wide control on the right of the header row is the Follow button, and a post you
     * are offered a Follow button for is by definition one you do not follow. The "More
     * options" button lives there too, but it is a small square, not a wide pill.
     */
    private fun hasFollowButton(head: Header, tags: List<Tag>, content: Bounds): Boolean =
        tags.any {
            it.bounds.verticalOverlap(head.avatar) * 2 >= it.bounds.height &&
                it.bounds.left >= content.left + content.width * FOLLOW_SIDE &&
                it.bounds.width >= content.width * MIN_FOLLOW_WIDTH
        }

    private fun hide(enabled: Boolean) = if (enabled) Verdict.HIDE else Verdict.KEEP

    /**
     * The page between its own two navigation bars — the header with the logo at the top,
     * and the Home/Explore/Reels strip at the bottom. Both are found by shape, with their
     * labels as a fallback for a layout whose shape does not read.
     */
    private fun contentRegion(tags: List<Tag>, web: UiNode): Bounds? {
        val maxBar = web.bounds.height * MAX_BAR
        val short = tags.filter { it.bounds.height <= maxBar }
        val rows = rows(short)

        val top = topBar(short, web) ?: namedBar(tags, web, maxBar, upper = true)
        val bottom = navBar(rows, web) ?: namedBar(tags, web, maxBar, upper = false)
        if (top == null && bottom == null) return null
        return Bounds(
            web.bounds.left,
            top ?: web.bounds.top,
            web.bounds.right,
            bottom ?: web.bounds.bottom,
        )
    }

    /**
     * Where the site's own header ends: the lowest edge of anything short that starts in
     * the header band — but only once something is actually flush with the top of the
     * page, so that a feed scrolled under a hidden header is not mistaken for one.
     */
    private fun topBar(short: List<Tag>, web: UiNode): Int? {
        val band = web.bounds.top + web.bounds.height * MAX_BAR
        if (short.none { it.bounds.top <= web.bounds.top + TOP_FLUSH }) return null
        return short.filter { it.bounds.top < band }.maxOfOrNull { it.bounds.bottom }
    }

    /**
     * Where the site's own bottom navigation begins: a row of equal-height controls
     * spanning the page, hugging its bottom edge. A post's like/comment/share row is the
     * same shape and can sit just as low, so the row has to reach the bottom edge and
     * cover most of the width before it counts.
     */
    private fun navBar(rows: Map<Pair<Int, Int>, List<Tag>>, web: UiNode): Int? {
        val zone = web.bounds.bottom - web.bounds.height * BAR_ZONE
        val flush = web.bounds.height * BAR_FLUSH
        return rows.entries
            .filter { (row, members) ->
                val (top, bottom) = row
                members.size >= MIN_BAR_ITEMS &&
                    top >= zone &&
                    web.bounds.bottom - bottom <= flush &&
                    span(members) >= web.bounds.width * MIN_BAR_WIDTH
            }
            .minOfOrNull { it.key.first }
    }

    /** The same two bars, found by what they say, for a layout whose shape did not read. */
    private fun namedBar(tags: List<Tag>, web: UiNode, maxBar: Double, upper: Boolean): Int? {
        val middle = (web.bounds.top + web.bounds.bottom) / 2
        val bars = tags.filter { it.text in NAV_ITEMS && it.bounds.height <= maxBar }
        return if (upper) {
            bars.filter { it.bounds.top < middle }.maxOfOrNull { it.bounds.bottom }
        } else {
            bars.filter { it.bounds.top > middle }.minOfOrNull { it.bounds.top }
        }
    }

    /**
     * The row of story bubbles above the feed: tiles that share a top and a bottom, too
     * tall to be a bar, wide enough between them to be a row, and above the first post.
     */
    private fun storiesRow(tags: List<Tag>, content: Bounds, firstHead: Int?): Bounds? {
        val ceiling = firstHead ?: content.bottom
        val band = rows(tags).entries
            .filter { (row, members) ->
                val (top, bottom) = row
                val height = bottom - top
                members.size >= MIN_STORY_TILES &&
                    height >= content.height * MIN_STORY_HEIGHT &&
                    height <= content.height * MAX_STORY_HEIGHT &&
                    top >= content.top &&
                    top - content.top <= content.height * STORY_ZONE &&
                    bottom <= ceiling &&
                    span(members) >= content.width * MIN_STORY_WIDTH
            }
            .minByOrNull { it.key.first }
            ?.let { Bounds(content.left, it.key.first, content.right, it.key.second) }
        if (band != null) return band

        return tags
            .filter { tag -> STORY.any { it.matches(tag.text) } && tag.bounds.top >= content.top }
            .map { it.bounds }
            .reduceOrNull { a, b -> a.union(b) }
            ?.let { Bounds(content.left, it.top, content.right, it.bottom) }
            ?.takeIf { !it.isEmpty && it.bottom <= content.bottom }
    }

    /**
     * Post headers, top to bottom.
     *
     * The labels are tried first, because when they read they also name the author. Shape
     * is what answers when they do not — a Russian, Spanish or Japanese feed says nothing
     * this file recognises, and finding no headers at all is the one failure that costs
     * the reader their whole feed rather than one post.
     */
    private fun headers(tags: List<Tag>, content: Bounds): List<Header> {
        val inside = tags.filter { it.bounds.top >= content.top && it.bounds.top < content.bottom }
        val named = inside.mapNotNull { tag ->
            avatarName(tag.text)?.let { Header(tag.bounds, it) }
        }
        val found = named.ifEmpty {
            inside.filter { isAvatar(it, inside, content) }
                .map { Header(it.bounds, nameBeside(it, inside)) }
        }
        return found.sortedBy { it.avatar.top }
            .fold(mutableListOf()) { heads, head ->
                if (heads.isEmpty() || head.avatar.top - heads.last().avatar.top > SAME_HEADER) {
                    heads += head
                }
                heads
            }
    }

    private fun avatarName(text: String): String? =
        AVATAR.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }

    /**
     * A post header's avatar: a small square image at the left margin, alone in its own
     * wrapper, with the author's name on the same line. Every clause is there because
     * something else on the page passes without it — the like button is an equally square
     * control at the same margin but one of eight in a row; the "Tags" badge inside a
     * photo is alone and square but has nothing beside it; the thumbnail in "Liked by …"
     * has both but is visibly smaller.
     */
    private fun isAvatar(tag: Tag, tags: List<Tag>, content: Bounds): Boolean {
        val box = tag.bounds
        if (tag.siblings > MAX_AVATAR_SIBLINGS) return false
        if (box.width <= 0 || box.height <= 0) return false
        if (kotlin.math.abs(box.width - box.height) > maxOf(box.width, box.height) * SQUARE) {
            return false
        }
        if (box.width < content.width * MIN_AVATAR || box.width > content.width * MAX_AVATAR) {
            return false
        }
        if (box.left - content.left > content.width * AVATAR_MARGIN) return false
        return nameBeside(tag, tags) != null
    }

    /** The nearest label to the right of [tag] on the same line — the author's name. */
    private fun nameBeside(tag: Tag, tags: List<Tag>): String? =
        tags.filter {
            it.bounds.left >= tag.bounds.right &&
                it.bounds.verticalOverlap(tag.bounds) * 2 >= tag.bounds.height
        }
            .minByOrNull { it.bounds.left }
            ?.text

    /** Labels grouped by the exact top and bottom they share — one entry per laid-out row. */
    private fun rows(tags: List<Tag>): Map<Pair<Int, Int>, List<Tag>> =
        tags.groupBy { it.bounds.top to it.bounds.bottom }

    /** How much of the page's width a row's members reach across, end to end. */
    private fun span(members: List<Tag>): Int {
        val left = members.minOf { it.bounds.left }
        val right = members.maxOf { it.bounds.right }
        return right - left
    }

    private fun label(node: UiNode): String? =
        (node.contentDesc ?: node.text)?.replace(' ', ' ')?.trim()?.takeIf { it.isNotEmpty() }
}
