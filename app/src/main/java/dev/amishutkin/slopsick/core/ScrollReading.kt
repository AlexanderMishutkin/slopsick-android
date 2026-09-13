package dev.amishutkin.slopsick.core

import kotlin.math.abs

/**
 * What a scroll event says about where the feed went.
 *
 * An accessibility scroll event carries a delta on both axes, and until now only the
 * vertical one was read. Everything sideways therefore came back as "distance unknown",
 * and the answer to an unknown distance is to cover the whole feed until the next scan —
 * so swiping across a carousel to see the second photo whitened the post being read.
 *
 * A swipe is never exactly horizontal. The tolerance is the reader's own: anything within
 * 15° of the horizontal moved the feed nowhere worth repainting for.
 */
object ScrollReading {

    /** What `AccessibilityEvent.getScrollDeltaX/Y` return when the view did not set one. */
    const val UNSET = -1

    /** tan 15°, as a fraction, so the angle test needs no floating point. */
    private const val TAN15_NUMER = 268
    private const val TAN15_DENOM = 1000

    /**
     * Whether the scroll went sideways rather than along the feed.
     *
     * Only answerable for a view that reports its horizontal delta. One that reports
     * neither delta is still "unknown", because the alternative — assuming nothing moved —
     * leaves a stale cover over a feed that has scrolled, and a stale cover is the failure
     * that shows the feed underneath it.
     */
    fun sideways(dx: Int, dy: Int): Boolean {
        if (dx == 0 || dx == UNSET) return false
        val down = if (dy == UNSET) 0 else abs(dy)
        return down.toLong() * TAN15_DENOM <= abs(dx).toLong() * TAN15_NUMER
    }
}
