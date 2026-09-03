package dev.amishutkin.slopsick.core

/** An app whose feed this tool knows how to read. */
enum class TargetApp(val packageName: String) {
    INSTAGRAM("com.instagram.android"),
    LINKEDIN("com.linkedin.android"),
    YOUTUBE("com.google.android.youtube"),
    CHROME("com.android.chrome");

    companion object {
        fun of(packageName: String?): TargetApp? =
            entries.firstOrNull { it.packageName == packageName }
    }
}

/** Which screen of the app is showing. */
enum class Surface {
    /** The main feed, with posts to judge one by one. */
    FEED,

    /**
     * A wall of short video — Instagram's Reels player, YouTube's Shorts player.
     * Nothing here was chosen, so none of it is judged.
     */
    REELS,

    /**
     * A grid of things the algorithm picked: Instagram's Explore tab. Same idea as
     * REELS — there is nothing in it you asked for.
     */
    EXPLORE,

    /** A profile, a chat, settings — nothing this app has an opinion about. */
    OTHER,
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
    REELS,
    EXPLORE,
    SHORTS_SHELF,
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
    val surface: Surface = Surface.FEED,
    /**
     * Covered outright, whatever the feed says — a Reels or Shorts player, Instagram's
     * Explore grid, a Shorts shelf wedged into YouTube's home feed. None of these is a
     * list of things you chose, so there is nothing in them to judge one by one.
     */
    val blackouts: List<Bounds> = emptyList(),
    /**
     * Covered *and* made untappable. Painting over the Reels tab would only hide it: the
     * main overlay lets touches through so that scrolling still works, so a blind tap
     * would still open Reels. These regions get a small window of their own that
     * swallows the touch.
     */
    val blockers: List<Bounds> = emptyList(),
    /**
     * The region between the app's own bars — everything that may be painted on this
     * screen. Null when the analyzer had no opinion about the screen at all.
     *
     * Carried on the scan because the overlay is repainted between scans, while the feed
     * is moving, from nothing but the last scan and how far it has scrolled since. That
     * projection has to know where the navigation bar is without re-reading the tree.
     */
    val safe: Bounds? = null,
) {
    val hasFeed: Boolean get() = feedBounds != null && !feedBounds.isEmpty

    val isEmpty: Boolean
        get() = !hasFeed && blackouts.isEmpty() && blockers.isEmpty()

    companion object {
        fun none(app: TargetApp) = FeedScan(app, null, emptyList(), Surface.OTHER)
    }
}

/** User-facing switches. Defaults match the browser extension's shipped defaults. */
data class Settings(
    /**
     * Master switch per app — the only three the settings screen shows. Off means this
     * app is not touched at all.
     */
    val instagram: Boolean = true,
    val linkedIn: Boolean = true,
    val youtube: Boolean = true,

    // The flags below are not on the settings screen. They are the shape of what each
    // app's switch means, and they are defaults rather than questions: a screen of eleven
    // switches is a screen nobody reads. They stay as fields because the analyzers are
    // tested through them, and because deciding differently later is a one-line change.
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
    /**
     * Instagram only. Covers the Reels player and makes the Reels tab untappable —
     * Instagram opens straight into Reels often enough that hiding the button alone
     * would not be enough.
     */
    val hideReels: Boolean = true,
    /** YouTube only. Covers the Shorts player and makes the Shorts tab untappable. */
    val hideShorts: Boolean = true,
    /**
     * Instagram only. Covers the Explore grid, keeping the search bar: searching for
     * something is a thing you chose to do, scrolling what the grid offers is not.
     */
    val hideExplore: Boolean = true,
    /** Hide the interstitial cards LinkedIn injects ("People you may know", job prompts). */
    val hideFeedModules: Boolean = true,

    /**
     * Put a report button on every covered region.
     *
     * Everything this tool gets wrong, it gets wrong about a particular screen on a
     * particular build of a particular app, and none of that survives being described
     * from memory. The button writes the tree, the verdicts and a screenshot to a folder
     * on the device — see `BugReporter`. It stays off the lock's list below on purpose:
     * turning reporting off does not uncover anything.
     */
    val reportButtons: Boolean = true,

    /**
     * Wall-clock time until which the switches cannot be turned down, as
     * `System.currentTimeMillis()`.
     *
     * The point of a lock you can undo in two taps is hard to see, so while it holds, a
     * switch that is on cannot be turned off. Turning *more* on is always allowed, and so
     * is extending the lock — the only thing being prevented is the moment of weakness.
     *
     * This is a promise the app makes to you, not a security measure: the accessibility
     * toggle in Android's own settings is always there, and nothing here tries to make
     * that harder.
     */
    val lockedUntil: Long = 0L,
) {
    fun lockedAt(now: Long) = now < lockedUntil

    /** True when [next] would reduce what is covered — the thing a lock prevents. */
    fun loosenedBy(next: Settings): Boolean =
        FLAGS.any { flag -> flag(this) && !flag(next) }

    private companion object {
        val FLAGS: List<(Settings) -> Boolean> = listOf(
            { it.instagram }, { it.linkedIn }, { it.youtube },
            { it.hideSuggested }, { it.hidePromoted }, { it.hideNetworkActivity },
            { it.hideStoriesTray }, { it.hideReels }, { it.hideShorts },
            { it.hideExplore }, { it.hideFeedModules },
        )
    }
}
