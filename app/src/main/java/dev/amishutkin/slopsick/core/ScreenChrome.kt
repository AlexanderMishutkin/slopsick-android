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
 *  3. **By giving up safely.** Found neither way, the bottom [NO_NAV_MARGIN] of the
 *     screen is left alone regardless. Covering a little less than we could is a bad
 *     day; covering the navigation bar is a bricked app.
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
) {
    /** First row that may be painted. */
    val ceiling: Int get() = maxOf(topBar?.bottom ?: screen.top, screen.top)

    /** First row that may *not* be painted. */
    val floor: Int
        get() = minOf(
            navBar?.top ?: (screen.bottom - (screen.height * NO_NAV_MARGIN).toInt()),
            screen.bottom,
        )

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

        /** How tall a bar may be, as a fraction of the screen. */
        private const val MIN_BAR = 0.025
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
            return ScreenChrome(
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
