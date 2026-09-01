package dev.amishutkin.focustube.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lock stops a switch being turned *down* for a while. It is deliberately not a
 * security measure — Android's own accessibility toggle is always there — so all it has
 * to do is notice the difference between backing out and leaning in.
 */
class LockTest {

    private val now = 1_000_000L
    private val locked = Settings(lockedUntil = now + 60_000)

    @Test
    fun `a lock in the future holds and one in the past does not`() {
        assertTrue(locked.lockedAt(now))
        assertFalse(locked.lockedAt(now + 60_001))
        assertFalse(Settings().lockedAt(now))
    }

    @Test
    fun `turning an app off is a loosening`() {
        assertTrue(locked.loosenedBy(locked.copy(instagram = false)))
        assertTrue(locked.loosenedBy(locked.copy(youtube = false)))
        assertTrue(locked.loosenedBy(locked.copy(hideShorts = false)))
    }

    @Test
    fun `turning something on is not`() {
        // Leaning in is always allowed, lock or no lock.
        val partly = Settings(hideNetworkActivity = false, hideStoriesTray = false)
        assertFalse(partly.loosenedBy(partly.copy(hideNetworkActivity = true)))
        assertFalse(partly.loosenedBy(partly.copy(hideStoriesTray = true)))
    }

    @Test
    fun `changing nothing is not a loosening`() {
        assertFalse(locked.loosenedBy(locked))
    }

    @Test
    fun `extending the lock is not a loosening`() {
        assertFalse(locked.loosenedBy(locked.copy(lockedUntil = locked.lockedUntil + 60_000)))
    }

    @Test
    fun `every switch is covered by the check`() {
        // A flag added to Settings and forgotten here would be silently unlockable.
        val all = Settings(
            instagram = true, linkedIn = true, youtube = true,
            hideSuggested = true, hidePromoted = true, hideNetworkActivity = true,
            hideStoriesTray = true, hideReels = true, hideShorts = true,
            hideExplore = true, hideFeedModules = true,
        )
        val none = Settings(
            instagram = false, linkedIn = false, youtube = false,
            hideSuggested = false, hidePromoted = false, hideNetworkActivity = false,
            hideStoriesTray = false, hideReels = false, hideShorts = false,
            hideExplore = false, hideFeedModules = false,
        )
        assertTrue(all.loosenedBy(none))
        // Turning each one off on its own must be caught, not just all at once.
        val singles = listOf(
            all.copy(instagram = false), all.copy(linkedIn = false), all.copy(youtube = false),
            all.copy(hideSuggested = false), all.copy(hidePromoted = false),
            all.copy(hideNetworkActivity = false), all.copy(hideStoriesTray = false),
            all.copy(hideReels = false), all.copy(hideShorts = false),
            all.copy(hideExplore = false), all.copy(hideFeedModules = false),
        )
        for (one in singles) assertTrue(all.loosenedBy(one))
    }
}
