package dev.amishutkin.focustube.core

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
 * honest position is that this analyzer is English-only, and it says so out loud rather
 * than silently misclassifying: when no signal matches at all, the verdict is UNKNOWN
 * and the item stays covered.
 */
object LinkedInAnalyzer {

    private const val LAZY_COLUMN = "lazyColumn"
    private const val TOP_BAR = "home_top_bar"
    private const val BOTTOM_BAR = "home_bottom_bar"

    // --- localised signals -------------------------------------------------------
    // Everything below is language-dependent. Grouped here so the damage is visible
    // and so translating the app is a matter of extending one object.

    private val PROMOTED = listOf("Promoted", "Sponsored")

    /** Controls that only appear on someone you have no relationship with. */
    private val FOLLOW_CONTROL = listOf("Follow ", "Invite ")

    /** The feed explaining its own guess. */
    private val FEED_GUESS = listOf(
        "Because you recently followed",
        "Suggested for you",
        "Suggested",
        "Trending",
        "Recommended for you",
    )

    /** Your network reacting to a stranger's post, rather than posting themselves. */
    private val ACTIVITY = listOf(" commented", " likes this", " reposted", " replied")

    /** Interstitial cards that are not posts at all. */
    private val MODULES = listOf(
        "People you may know",
        "Recommended for you",
        "Prepare for your job search",
        "Add to your feed",
    )

    /** A first-degree connection: someone you actually know. */
    private val FIRST_DEGREE = Regex("""•\s*1st""")
    private val VIEW_PROFILE = Regex("""^View (.+?)(?:’s|'s)? profile""")
    private val OTHER_DEGREE = Regex("""•\s*(2nd|3rd\+?)""")

    fun analyze(root: UiNode, settings: Settings = Settings()): FeedScan {
        val column = root.walk().firstOrNull { it.viewId?.endsWith(LAZY_COLUMN) == true }
            ?: return FeedScan.none(TargetApp.LINKEDIN)
        val content = contentRegion(root, column)
        if (content.isEmpty) return FeedScan.none(TargetApp.LINKEDIN)

        val items = column.children
            .filterNot { it.bounds.isEmpty }
            .map { classify(it, settings) }
            .mapNotNull { it.clipTo(content) }

        return FeedScan(TargetApp.LINKEDIN, content, items)
    }

    private fun classify(item: UiNode, settings: Settings): FeedItem {
        val labels = item.labels()
        val blob = labels.joinToString(" | ")
        val bounds = item.bounds
        // Only a name lifted from "View <name>'s profile" is stable and unique enough to
        // key the ledger on. The looser guess below is good enough to show in a log but
        // would happily return "13h" for two unrelated posts, and two posts sharing an
        // identity would inherit each other's verdicts.
        val profile = profileActorOf(labels)
        val actor = profile ?: looseActorOf(labels)
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
        // A post with an author but no follow control and no degree marker: a page you
        // follow. This mirrors the browser extension's rule, which treats the absence of
        // a Follow/Connect control as evidence of a relationship.
        if (actor != null) {
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
            VIEW_PROFILE.find(label)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        return null
    }

    private fun looseActorOf(labels: List<String>): String? =
        labels.firstOrNull { it.contains("\u2022") && !it.startsWith("\u2022") }
            ?.substringBefore("\u2022")?.trim()?.takeIf { it.isNotEmpty() }

    private fun contentRegion(root: UiNode, column: UiNode): Bounds {
        val top = root.findById(TOP_BAR)?.bounds?.bottom ?: column.bounds.top
        val bottom = root.findById(BOTTOM_BAR)?.bounds?.top ?: column.bounds.bottom
        return Bounds(
            left = column.bounds.left,
            top = maxOf(column.bounds.top, top),
            right = column.bounds.right,
            bottom = minOf(column.bounds.bottom, bottom),
        )
    }
}
