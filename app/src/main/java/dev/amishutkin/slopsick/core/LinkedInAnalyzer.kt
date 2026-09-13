package dev.amishutkin.slopsick.core

/**
 * Reads LinkedIn's main feed.
 *
 * LinkedIn is the awkward one. The feed is Jetpack Compose driven by server-defined UI,
 * and post content carries **no view ids at all** — only the scroll container does
 * (`sdui:lazyColumn`). Grouping is therefore easier than Instagram (one child of the
 * lazy column is exactly one feed item) but classification is harder: every signal is a
 * string, and strings are localised.
 *
 * That is a real regression against the browser extension, which classifies LinkedIn
 * structurally and so works in any interface language. Until LinkedIn exposes ids, the
 * honest position is that this analyzer only knows the languages listed below, and it
 * says so out loud rather than silently misclassifying: when no signal matches at all,
 * the verdict is UNKNOWN and the item stays covered.
 *
 * Which makes the fail-open path the one to watch. A capture from a phone running
 * LinkedIn in Russian matched none of the English signals — "X liked this" is
 * "X отметил(а), что нравится этот контент" — and every post came back KEEP anyway,
 * because the loose actor guess below happily read "13 ч." out of a timestamp and a post
 * with an author and no follow control is taken to be someone you know. So the rule that
 * keeps a post on the strength of an author now insists on an author read from a "view
 * profile" label, which is a sentence about a person rather than any label with a bullet
 * in it. An unrecognised language now costs a covered feed, which is visible and
 * fixable, rather than an uncovered one, which is not.
 */
object LinkedInAnalyzer {

    private const val LAZY_COLUMN = "lazyColumn"
    private const val TOP_BAR = "home_top_bar"
    private const val BOTTOM_BAR = "home_bottom_bar"

    /**
     * The navigation drawer, slid out over the feed. The feed is still in the tree behind
     * it, still on the feed tab, still perfectly analysable — and painting its verdicts
     * puts covers over the panel in front, which is where your own profile, your settings
     * and the way to everything else live. Reported from the phone as "I can't reach my
     * own profile"; the covers were correct and drawn over the wrong screen.
     *
     * Present only when the drawer is open: none of the 15 captured LinkedIn feeds has it.
     */
    private val DRAWER = listOf("home_nav_panel_fragment", "home_drawer_frame")

    /**
     * Every LinkedIn tab is a lazy column — Jobs and Search look exactly like the feed
     * from the tree's point of view. Which tab is current is the only thing that tells
     * them apart, and job listings are not the feed's idea of what you should look at.
     */
    private const val FEED_TAB = "tab_feed"

    // --- localised signals -------------------------------------------------------
    // Everything below is language-dependent. Grouped here so the damage is visible
    // and so translating the app is a matter of extending one object.

    private val PROMOTED = listOf("Promoted", "Sponsored", "Продвигается", "Реклама")

    /** Controls that only appear on someone you have no relationship with. */
    private val FOLLOW_CONTROL = listOf("Follow ", "Invite ", "Отслеживать", "Пригласить")

    /** The feed explaining its own guess. */
    private val FEED_GUESS = listOf(
        "Because you recently followed",
        "Suggested for you",
        "Suggested",
        "Trending",
        "Recommended for you",
        "Вы недавно подписались",
        "Рекомендовано для вас",
        "Рекомендуем",
        "Популярное",
    )

    /**
     * Your network reacting to a stranger's post, rather than posting themselves. This is
     * the most-used rule in the file and the one the Russian capture proved was missing:
     * "нравится этот контент" is the whole of "liked this".
     */
    private val ACTIVITY = listOf(
        " commented", " likes this", " reposted", " replied",
        "нравится этот контент", "прокомментировал", "поделил", "ответил", "репостнул",
    )

    /** Interstitial cards that are not posts at all. */
    private val MODULES = listOf(
        "People you may know",
        "Recommended for you",
        "Prepare for your job search",
        "Add to your feed",
        "Люди, которых вы можете знать",
        "Добавить в ленту",
        "Подготовьтесь к поиску работы",
    )

    /**
     * The degree marker, which every language writes as a bullet and a number: "• 1st",
     * "• 2nd", "• 3-й", "• 3-й+". Anchored to the end of its label so a bullet in the
     * middle of a sentence — "13h • Visibility: Global" — cannot pass for one.
     */
    private val FIRST_DEGREE = Regex("""•\s*1(?:st|-й|-я|°|º)?\s*(?=\||$)""")
    private val OTHER_DEGREE = Regex("""•\s*[23](?:nd|rd|-й|-я|°|º)?\+?\s*(?=\||$)""")

    /** "View <name>'s profile", in each language that has been seen. */
    private val VIEW_PROFILE = listOf(
        Regex("""^View (.+?)(?:’s|'s)? profile"""),
        Regex("""^(?:Про|По)смотреть профиль участника (.+)$"""),
    )

    /**
     * Longest a label can be and still be part of the post's furniture rather than its
     * body. Every LinkedIn signal is a string, and the post text is in the same list of
     * strings — without this, somebody writing "Promoted to Senior Engineer" or "I
     * commented on this last week" in a post would have it classified by its own prose.
     * The fixtures cannot catch that, because the anonymiser replaces long bodies.
     */
    private const val MAX_CHROME_LABEL = 80

    fun analyze(root: UiNode, settings: Settings = Settings()): FeedScan {
        if (!settings.linkedIn) return FeedScan.none(TargetApp.LINKEDIN)
        val feedTab = root.findById(FEED_TAB)?.takeIf { it.selected }
            ?: return FeedScan.none(TargetApp.LINKEDIN)
        // Something is open in front of the feed. Whatever the feed says, it is not what
        // the reader is looking at, and it is not ours to paint over.
        if (DRAWER.any { id -> root.walk().any { it.hasId(id) && !it.bounds.isEmpty } }) {
            return FeedScan.none(TargetApp.LINKEDIN)
        }

        val column = root.walk().firstOrNull { it.viewId?.endsWith(LAZY_COLUMN) == true }
            ?: return FeedScan.none(TargetApp.LINKEDIN)
        val chrome = ScreenChrome.of(root, topBarId = TOP_BAR, navBarId = BOTTOM_BAR)
        val content = contentRegion(column, feedTab, chrome)
        if (content.isEmpty) return FeedScan.none(TargetApp.LINKEDIN)

        val items = column.children
            .filterNot { it.bounds.isEmpty }
            .map { classify(it, settings) }
            .mapNotNull { it.clipTo(content) }

        return FeedScan(
            TargetApp.LINKEDIN, content, items,
            safe = chrome.safe, navBar = chrome.navBar, barless = chrome.barless,
        )
    }

    private fun classify(item: UiNode, settings: Settings): FeedItem {
        val all = item.labels()
        val labels = all.filter { it.length <= MAX_CHROME_LABEL }
        val blob = labels.joinToString(" | ")
        val bounds = item.bounds
        // Only a name lifted from "View <name>'s profile" is stable and unique enough to
        // key the ledger on. The looser guess below is good enough to show in a log but
        // would happily return "13h" for two unrelated posts, and two posts sharing an
        // identity would inherit each other's verdicts.
        val profile = profileActorOf(labels)
        val actor = profile ?: looseActorOf(labels)
        if (all.isEmpty()) {
            return FeedItem(bounds, Verdict.UNKNOWN, Reason.NO_SIGNAL, null, null)
        }
        val identity = profile?.let { "li:$it" }

        fun verdict(reason: Reason, hidden: Boolean) =
            FeedItem(bounds, if (hidden) Verdict.HIDE else Verdict.KEEP, reason, actor, identity)

        if (PROMOTED.any { p -> labels.any { it.contains(p) } }) {
            return verdict(Reason.PROMOTED, settings.hidePromoted)
        }
        if (MODULES.any { m -> labels.any { it.startsWith(m) } }) {
            return verdict(Reason.FEED_MODULE, settings.hideFeedModules)
        }
        if (FEED_GUESS.any { g -> labels.any { it.startsWith(g) } }) {
            return verdict(Reason.SUGGESTED, settings.hideSuggested)
        }
        // Your network reacting to a stranger's post, rather than posting themselves.
        if (ACTIVITY.any { a -> labels.any { it.contains(a) } }) {
            return verdict(Reason.NETWORK_ACTIVITY, settings.hideNetworkActivity)
        }
        if (FIRST_DEGREE.containsMatchIn(blob)) {
            return verdict(Reason.CONNECTION, false)
        }
        val hasFollowControl = FOLLOW_CONTROL.any { f -> labels.any { it.startsWith(f) } }
        if (hasFollowControl || OTHER_DEGREE.containsMatchIn(blob)) {
            return verdict(Reason.SUGGESTED, settings.hideSuggested)
        }
        // A post with a named author but no follow control and no degree marker: a page
        // you follow. This mirrors the browser extension's rule, which treats the absence
        // of a Follow/Connect control as evidence of a relationship — but it only holds
        // if the author was actually read. The loose guess below returns "13h" for a
        // timestamp, and a feed in a language none of the lists above cover is a feed of
        // nothing but loose guesses; keeping every post in it is the one outcome worse
        // than covering them all.
        if (profile != null) {
            return verdict(Reason.CONNECTION, false)
        }
        // No author, no controls, no labels at all — a card still loading, or a layout
        // this build has never seen. Covered, and left for the ledger to resolve if the
        // same item was judged while it was whole.
        return FeedItem(bounds, Verdict.UNKNOWN, Reason.NO_SIGNAL, null, null)
    }

    /**
     * The person or page whose post this is.
     *
     * "View <name>'s profile" survives a post being scrolled halfway off the top, which
     * the degree marker and the follow button do not, so it is what the ledger keys on.
     * Anything less specific is display-only.
     */
    private fun profileActorOf(labels: List<String>): String? {
        for (label in labels) {
            VIEW_PROFILE.firstNotNullOfOrNull { it.find(label)?.groupValues?.get(1) }
                ?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        return null
    }

    private fun looseActorOf(labels: List<String>): String? =
        labels.firstOrNull { it.contains("\u2022") && !it.startsWith("\u2022") }
            ?.substringBefore("\u2022")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * LinkedIn collapses its top bar on scroll, and then the lazy column reports the
     * whole screen — the navigation included. Three separate floors are taken and the
     * highest wins: the bar found by id or by shape ([ScreenChrome]), the feed tab
     * itself, which is on screen whenever this runs, and the column's own bottom.
     */
    private fun contentRegion(column: UiNode, feedTab: UiNode, chrome: ScreenChrome): Bounds =
        Bounds(
            left = column.bounds.left,
            top = maxOf(column.bounds.top, chrome.ceiling),
            right = column.bounds.right,
            bottom = minOf(minOf(column.bounds.bottom, chrome.floor), feedTab.bounds.top),
        )
}
