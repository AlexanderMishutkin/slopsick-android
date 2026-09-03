package dev.amishutkin.slopsick.core

/**
 * The app's own furniture — the bar across the top, the navigation bar along the
 * bottom — and the region between them, which is the only part of the screen this tool
 * is allowed to paint.
 *
 * Every analyzer used to find these by view id alone. That works until it doesn't: an
 * app updates, the id it used to publish is gone, `findById` returns null, and the
 * fallback was the scroll container's own bounds — which on every one of these apps runs
 * *behind* the navigation bar. The result is a black rectangle over the tab bar and an
 * app you can no longer navigate. That is the worst failure this tool has: covering too
 * much of a feed is an annoyance, covering the way out of it is a trap.
 *
 * So the bars are found three ways, in descending order of precision, and the most
 * conservative answer wins:
 *
 *  1. **By id**, when the app still publishes one.
 *  2. **By shape**, which is what actually makes a navigation bar recognisable: a strip
 *     flush with the bottom of the screen, as wide as the screen, a few percent of its
 *     height, holding three to six equally-sized controls side by side. No app in this
 *     set draws anything else like that, and no version bump changes it.
 *  3. **By giving up carefully.** Found neither way, the bar may still be *declared* — its
 *     id present in the tree, reporting bounds that cannot be true. On the phone this was
 *     reported from, LinkedIn's `home_bottom_bar` comes back as `[0,2712][1220,2712]` on
 *     some frames: zero height, pinned to the bottom of a 2712-pixel screen, while the bar
 *     is plainly on screen and being used. `bottom_nav_container` does worse and reports a
 *     top *below* its own bottom. An app that still says it has a bar is not offering us
 *     the space, so the bottom [NO_NAV_MARGIN] is left alone. Only when nothing declares a
 *     bar and nothing down there is even shaped like one does the feed run to the bottom
 *     of the display — which is the YouTube case, where the bar really has gone.
 *
 * None of this is trusted on its own. [FeedScan.navBar] carries the bar out to the
 * service, which remembers where it was and refuses to paint below it whatever a later
 * frame claims — see `OverlayController`. Three of these reports had the bar in exactly
 * the right place and the cover was painted over it anyway, so the last word belongs to
 * the thing holding the brush.
 *
 * Only the bottom bar gets the structural treatment. The same three rules were tried on
 * the top bar and had to be withdrawn: Instagram hides its toolbar on scroll, and with
 * it gone the shape test matches the first thing pinned to the top of the screen
 * instead — in the captured frames, a post's own like/comment row. That leaves a strip
 * of the post uncovered, which is the failure this whole file exists to avoid. So the
 * top bar is recognised by id or not at all, and when it is not there the cover simply
 * starts at the top of the feed, which is where the toolbar used to be.
 */
data class ScreenChrome(
    val screen: Bounds,
    /** The app's top bar, if it has one on this screen. */
    val topBar: Bounds?,
    /** The app's bottom navigation bar, if it has one on this screen. */
    val navBar: Bounds?,
    /**
     * The floor to use when no navigation bar was found — see [ScreenChrome] for why
     * that is not simply the bottom of the screen.
     */
    val fallbackFloor: Int = 0,
) {
    /**
     * True when this frame found no bar *and* is confident there is none — nothing
     * declared one, and nothing along the bottom is even shaped like one.
     *
     * The distinction matters downstream. A frame that merely failed to find a bar must
     * not be allowed to override where one was last seen; a frame that positively
     * established there is no bar may, and has to, or YouTube's feed stops an inch short
     * of the bottom every time its own bar scrolls away.
     */
    val barless: Boolean get() = navBar == null && fallbackFloor >= screen.bottom

    /** First row that may be painted. */
    val ceiling: Int get() = maxOf(topBar?.bottom ?: screen.top, screen.top)

    /** First row that may *not* be painted. */
    val floor: Int get() = minOf(navBar?.top ?: fallbackFloor, screen.bottom)

    /** Everything between the two bars. */
    val safe: Bounds get() = Bounds(screen.left, ceiling, screen.right, floor)

    /** [bounds] trimmed to [safe], or null when nothing of it is left. */
    fun clamp(bounds: Bounds): Bounds? {
        val region = safe
        val out = Bounds(
            left = maxOf(bounds.left, region.left),
            top = maxOf(bounds.top, region.top),
            right = minOf(bounds.right, region.right),
            bottom = minOf(bounds.bottom, region.bottom),
        )
        return out.takeIf { !it.isEmpty }
    }

    companion object {
        /** How much of the bottom of the screen is off limits when no bar is found. */
        private const val NO_NAV_MARGIN = 0.09

        /** A bar has to be flush with the bottom: within this much of it. */
        private const val BOTTOM_ZONE = 0.08

        /**
         * How tall a bar may be, as a fraction of the screen. The floor is not as low as
         * it could be: every bar measured off a device is at least 5% of the screen, and
         * anything thinner is a divider or a caption row, which must not be allowed to
         * stand in for a bar in [fallbackFloor].
         */
        private const val MIN_BAR = 0.04
        private const val MAX_BAR = 0.12

        /** How wide it has to be, as a fraction of the screen. */
        private const val MIN_WIDTH = 0.9

        /** How many controls make a navigation bar rather than a row of something else. */
        private const val MIN_TABS = 3
        private const val MAX_TABS = 7

        /** No single tab may take more than this much of the bar. */
        private const val MAX_TAB_WIDTH = 0.4

        /**
         * How unequal the tabs may be. A navigation bar divides its width evenly; the
         * thing that made this necessary is Instagram's like/comment/share row, which
         * also sits flush above the tab bar, is also full width, and is also a row of
         * side-by-side controls — but its buttons are 64 to 183 pixels wide.
         */
        private const val MAX_TAB_SPREAD = 1.25

        /** And they fill the bar, where a row of icons is bunched at one end. */
        private const val MIN_TAB_COVERAGE = 0.85

        /** Bounds drift by a pixel between a parent and a child that fills it. */
        private const val SLACK = 4

        /**
         * @param topBarId the app's own id for its top bar, when it publishes one
         * @param navBarId the app's own id for its bottom navigation
         */
        fun of(root: UiNode, topBarId: String? = null, navBarId: String? = null): ScreenChrome {
            val screen = screenOf(root)
            val byIdNav = byId(root, navBarId)
            val foundNav = findNavBar(root, screen)
            // Present in the tree at all, even reporting nonsense bounds: see [collapsed].
            val declared = navBarId != null && root.findById(navBarId) != null
            return ScreenChrome(
                fallbackFloor = fallbackFloor(root, screen, declared),
                screen = screen,
                // The top bar is recognised by id or not at all; see the file comment.
                topBar = byId(root, topBarId),
                // Whichever answer starts higher, so the bar keeps whatever margin
                // either of them claims for it.
                navBar = when {
                    byIdNav != null && foundNav != null ->
                        if (byIdNav.top <= foundNav.top) byIdNav else foundNav
                    else -> byIdNav ?: foundNav
                },
            )
        }

        /**
         * What to do when neither the id nor the shape found a bar.
         *
         * Two very different situations look the same from here. Either there is no bar
         * on this screen — YouTube hides its own the moment you scroll, and then the feed
         * really does run to the bottom of the display — or there is one and it was not
         * recognised, which is the failure this file exists to prevent.
         *
         * They can be told apart without recognising the bar itself: is there *anything*
         * down there shaped like one? A full-width strip of the right height, whatever it
         * holds. If there is, stay off the bottom [NO_NAV_MARGIN] of the screen and accept
         * covering a little less than we could. If there is nothing of the sort, the space
         * is content and covering it is right — which is the difference between hiding a
         * Shorts shelf and hiding all but the last inch of one.
         */
        private fun fallbackFloor(root: UiNode, screen: Bounds, declared: Boolean): Int {
            if (screen.isEmpty) return screen.bottom
            // The app still says it has a bar on this screen; it is only lying about
            // where. Nothing about that means the space is ours.
            if (declared) return screen.bottom - (screen.height * NO_NAV_MARGIN).toInt()
            val minBottom = screen.bottom - (screen.height * BOTTOM_ZONE).toInt()
            val barLike = root.walk().any {
                it.bounds.bottom >= minBottom && isBarShaped(it.bounds, screen)
            }
            return if (barLike) screen.bottom - (screen.height * NO_NAV_MARGIN).toInt()
            else screen.bottom
        }

        private fun byId(root: UiNode, id: String?): Bounds? =
            id?.let { root.findById(it)?.bounds?.takeIf { b -> !b.isEmpty } }

        /**
         * The whole display. The root of an accessibility tree normally spans it, but a
         * dialog's root does not, so the widest node wins.
         */
        private fun screenOf(root: UiNode): Bounds =
            root.walk().map { it.bounds }.filterNot { it.isEmpty }
                .maxByOrNull { it.width.toLong() * it.height } ?: root.bounds

        /**
         * A strip along the bottom holding a handful of equal controls side by side.
         *
         * The topmost match wins: several nested views describe the same bar, and the
         * one that starts highest is the one that leaves the most of it uncovered.
         */
        private fun findNavBar(root: UiNode, screen: Bounds): Bounds? {
            if (screen.isEmpty) return null
            val minBottom = screen.bottom - (screen.height * BOTTOM_ZONE).toInt()
            return root.walk()
                .filter { it.bounds.bottom >= minBottom && isBarShaped(it.bounds, screen) }
                .filter { hasTabs(it) }
                .map { it.bounds }
                .minByOrNull { it.top }
        }

        private fun isBarShaped(bounds: Bounds, screen: Bounds): Boolean =
            bounds.width >= screen.width * MIN_WIDTH &&
                bounds.height >= screen.height * MIN_BAR &&
                bounds.height <= screen.height * MAX_BAR

        /**
         * Whether this node's content is a row of tab buttons.
         *
         * A bar is often a wrapper around a wrapper around the row that actually holds
         * the buttons — YouTube's `pivot_bar` is one — so single children that fill their
         * parent are stepped through before the row is examined.
         */
        private fun hasTabs(node: UiNode): Boolean {
            val bar = node.bounds
            var row = node
            var guard = 0
            while (row.children.size == 1 && guard++ < 4) row = row.children.first()

            val tabs = row.children.filterNot { it.bounds.isEmpty }
            if (tabs.size < MIN_TABS || tabs.size > MAX_TABS) return false

            val sized = tabs.all {
                it.bounds.width <= bar.width * MAX_TAB_WIDTH &&
                    it.bounds.height * 2 >= bar.height &&
                    it.bounds.top >= bar.top - SLACK &&
                    it.bounds.bottom <= bar.bottom + SLACK
            }
            if (!sized) return false

            val widths = tabs.map { it.bounds.width }
            val narrowest = widths.min()
            if (narrowest <= 0) return false
            if (widths.max() > narrowest * MAX_TAB_SPREAD) return false
            if (widths.sum() < bar.width * MIN_TAB_COVERAGE) return false

            // Side by side, not stacked: a column of list rows is not a navigation bar.
            val ordered = tabs.sortedBy { it.bounds.left }
            for (i in 1 until ordered.size) {
                if (ordered[i].bounds.left < ordered[i - 1].bounds.right - SLACK) return false
            }
            return true
        }
    }
}
