package dev.amishutkin.focustube.core

/** An app whose feed this tool knows how to read. */
enum class TargetApp(val packageName: String) {
    INSTAGRAM("com.instagram.android"),
    LINKEDIN("com.linkedin.android"),
    CHROME("com.android.chrome");

    companion object {
        fun of(packageName: String?): TargetApp? =
            entries.firstOrNull { it.packageName == packageName }
    }
}

enum class Verdict {
    /** Something you chose to see: an account you follow, a person you are connected to. */
    KEEP,

    /** Something the feed chose for you: a suggestion, an ad, a "people you may know" card. */
    HIDE,

    /**
     * Not enough of the item is on screen to tell — usually a post whose header has
     * scrolled off the top. Covered by default, so an unreadable feed can never leak
     * through as a readable one.
     */
    UNKNOWN,
}

/**
 * Why an item was judged the way it was. Kept as a value rather than a string so the
 * debug screen can show it and the tests can assert on it.
 */
enum class Reason {
    FOLLOWED,
    CONNECTION,
    NETWORK_ACTIVITY,
    SUGGESTED,
    PROMOTED,
    FEED_MODULE,
    STORIES_TRAY,
    OFF_SCREEN_HEADER,
    NO_SIGNAL,
}

data class FeedItem(
    val bounds: Bounds,
    val verdict: Verdict,
    val reason: Reason,
    /** Author handle or display name when one was found; for logs and the debug screen only. */
    val author: String? = null,
    /**
     * A key that identifies this post between frames, so a verdict reached while the
     * post was fully visible survives it being scrolled half off screen. Null when the
     * item carries nothing stable enough to key on.
     */
    val identity: String? = null,
) {
    val isHidden: Boolean get() = verdict != Verdict.KEEP
}

/**
 * The result of reading one frame of a feed.
 *
 * [feedBounds] is the region the feed occupies. Anything inside it that no item claims
 * is unclassified space, and the overlay covers it — see [OverlayPlan].
 */
data class FeedScan(
    val app: TargetApp,
    val feedBounds: Bounds?,
    val items: List<FeedItem>,
) {
    val hasFeed: Boolean get() = feedBounds != null && !feedBounds.isEmpty

    companion object {
        fun none(app: TargetApp) = FeedScan(app, null, emptyList())
    }
}

/** User-facing switches. Defaults match the browser extension's shipped defaults. */
data class Settings(
    /** Hide posts from accounts you do not follow. This is the whole point of the app. */
    val hideSuggested: Boolean = true,
    /** Hide ads. Untested against a real ad — see README. */
    val hidePromoted: Boolean = true,
    /**
     * LinkedIn only. Off: you still see what your network reacted to. On: only posts
     * from your connections and the pages you follow survive.
     */
    val hideNetworkActivity: Boolean = false,
    /** Hide the stories row at the top of Instagram's feed. */
    val hideStoriesTray: Boolean = false,
    /** Hide the interstitial cards LinkedIn injects ("People you may know", job prompts). */
    val hideFeedModules: Boolean = true,
)
