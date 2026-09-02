package dev.amishutkin.slopsick.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreTest {

    @Test
    fun `the Explore grid is covered whole`() {
        val scan = InstagramAnalyzer.analyze(instagramExploreScreen())
        assertEquals(Surface.EXPLORE, scan.surface)
        assertEquals(listOf(Bounds(0, 210, 1080, 2211)), OverlayPlan.cover(scan))
    }

    @Test
    fun `the search bar survives`() {
        // Searching for something is a thing you chose to do; scrolling what the grid
        // offers you is not. Covering the search bar would take away the good half.
        val covers = OverlayPlan.cover(InstagramAnalyzer.analyze(instagramExploreScreen()))
        assertTrue("the search bar is above y=210", covers.all { it.top >= 210 })
    }

    @Test
    fun `the tab bar survives`() {
        val covers = OverlayPlan.cover(InstagramAnalyzer.analyze(instagramExploreScreen()))
        assertTrue(covers.all { it.bottom <= 2211 })
    }

    @Test
    fun `search results are not the Explore grid`() {
        // Once you type, Instagram drops the explore action bar. Keying off the grid alone
        // would cover the results you went looking for.
        val scan = InstagramAnalyzer.analyze(instagramSearchResultsScreen())
        assertEquals(Surface.OTHER, scan.surface)
        assertTrue(OverlayPlan.cover(scan).isEmpty())
    }

    @Test
    fun `turning Explore off leaves it alone`() {
        val scan = InstagramAnalyzer.analyze(instagramExploreScreen(), Settings(hideExplore = false))
        assertTrue(scan.blackouts.isEmpty())
    }

    @Test
    fun `the covered grid is labelled`() {
        val scan = InstagramAnalyzer.analyze(instagramExploreScreen())
        assertEquals(Reason.EXPLORE, OverlayPlan.details(scan).single().reason)
    }
}
