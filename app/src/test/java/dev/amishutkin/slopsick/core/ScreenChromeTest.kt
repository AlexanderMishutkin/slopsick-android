package dev.amishutkin.slopsick.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation bar is the one thing that must never be painted over: cover a feed too
 * eagerly and you have a bad afternoon, cover the tab bar and the app is unusable until
 * you work out what did it.
 *
 * These tests are all about the failure the phone actually hit — the id being gone.
 */
class ScreenChromeTest {

    private val screen = Bounds(0, 0, 1080, 2400)

    /** A navigation bar with [tabs] equal buttons, under whatever id is given. */
    private fun navBar(id: String?, tabs: Int = 5, top: Int = 2211): UiNode {
        val width = 1080 / tabs
        return FakeNode(
            viewId = id,
            bounds = Bounds(0, top, 1080, 2337),
            children = (0 until tabs).map {
                FakeNode(bounds = Bounds(it * width, top, (it + 1) * width, 2337))
            },
        )
    }

    private fun screenWith(vararg children: UiNode) =
        FakeNode(bounds = screen, children = children.toList())

    @Test
    fun `the bar is found by its id`() {
        val chrome = ScreenChrome.of(
            screenWith(navBar("com.instagram.android:id/tab_bar")),
            navBarId = "tab_bar",
        )
        assertEquals(Bounds(0, 2211, 1080, 2337), chrome.navBar)
        assertEquals(2211, chrome.floor)
    }

    @Test
    fun `the bar is found by its shape when the app stops publishing an id`() {
        // This is the whole point of the file. An app update renames or drops the id and
        // the previous version's answer was "cover everything down to the bottom of the
        // scroll container", which is the navigation bar.
        val chrome = ScreenChrome.of(screenWith(navBar(id = null)), navBarId = "tab_bar")
        assertEquals(Bounds(0, 2211, 1080, 2337), chrome.navBar)
    }

    @Test
    fun `an unrecognised bar still keeps the bottom of the screen`() {
        // Something down there is bar-shaped, so whatever it is, stay off it.
        val unknown = FakeNode(bounds = Bounds(0, 2211, 1080, 2337))
        val chrome = ScreenChrome.of(screenWith(unknown), navBarId = "tab_bar")
        assertNull(chrome.navBar)
        assertTrue("a bar-shaped thing must not be painted over", chrome.floor <= 2211)
        assertTrue(chrome.floor > 2100)
    }

    @Test
    fun `with nothing bar-shaped down there, the feed runs to the bottom`() {
        // YouTube hides its own navigation bar on scroll, and then the last inch of the
        // screen really is feed. Leaving it uncovered showed as a strip of Shorts.
        val row = FakeNode(bounds = Bounds(0, 1200, 1080, 2337))
        val chrome = ScreenChrome.of(screenWith(row), navBarId = "pivot_bar")
        assertNull(chrome.navBar)
        assertEquals(2400, chrome.floor)
    }

    @Test
    fun `a thin divider at the bottom does not count as bar-shaped`() {
        val divider = FakeNode(bounds = Bounds(0, 2280, 1080, 2337))
        val chrome = ScreenChrome.of(screenWith(divider), navBarId = "pivot_bar")
        assertEquals(2400, chrome.floor)
    }

    @Test
    fun `a wrapper around the row of buttons is still recognised`() {
        // YouTube's pivot_bar wraps a LinearLayout that wraps the buttons.
        val inner = navBar(id = null)
        val wrapped = FakeNode(bounds = inner.bounds, children = listOf(inner))
        val chrome = ScreenChrome.of(screenWith(wrapped))
        assertNotNull(chrome.navBar)
    }

    @Test
    fun `a feed row at the bottom of the screen is not a navigation bar`() {
        // One wide child, not a row of equal ones.
        val row = FakeNode(
            bounds = Bounds(0, 2100, 1080, 2337),
            children = listOf(FakeNode(bounds = Bounds(0, 2100, 1080, 2337))),
        )
        assertNull(ScreenChrome.of(screenWith(row)).navBar)
    }

    @Test
    fun `a stack of list rows is not a navigation bar`() {
        val stacked = FakeNode(
            bounds = Bounds(0, 2280, 1080, 2337),
            children = (0 until 3).map { FakeNode(bounds = Bounds(0, 2280 + it * 19, 300, 2299 + it * 19)) },
        )
        assertNull(ScreenChrome.of(screenWith(stacked)).navBar)
    }

    @Test
    fun `a bar halfway up the screen is not the navigation bar`() {
        assertNull(ScreenChrome.of(screenWith(navBar(id = null, top = 1200))).navBar)
    }

    @Test
    fun `when both answers exist the one that covers less wins`() {
        // The id is on an outer view that starts a few pixels above the button row.
        val row = navBar(id = null)
        val outer = FakeNode(
            viewId = "com.linkedin.android:id/home_bottom_bar",
            bounds = Bounds(0, 2190, 1080, 2400),
            children = listOf(FakeNode(bounds = Bounds(0, 2190, 1080, 2193)), row),
        )
        val chrome = ScreenChrome.of(screenWith(outer), navBarId = "home_bottom_bar")
        assertEquals(2190, chrome.floor)
    }

    @Test
    fun `the top bar is taken from its id and never guessed`() {
        // Instagram hides its toolbar on scroll, and the first thing pinned to the top of
        // the screen after that is a post's own like row. Guessing there would leave a
        // strip of the post showing above the cover.
        val postRow = FakeNode(
            viewId = "com.instagram.android:id/row_feed_view_group_buttons",
            bounds = Bounds(0, 63, 1080, 162),
        )
        val chrome = ScreenChrome.of(screenWith(postRow), topBarId = "main_feed_action_bar")
        assertNull(chrome.topBar)
        assertEquals(0, chrome.ceiling)
    }

    @Test
    fun `clamping trims a region to what may be painted`() {
        val chrome = ScreenChrome.of(
            screenWith(navBar("com.instagram.android:id/tab_bar")),
            navBarId = "tab_bar",
        )
        assertEquals(Bounds(0, 100, 1080, 2211), chrome.clamp(Bounds(0, 100, 1080, 2400)))
        assertNull("nothing below the bar survives", chrome.clamp(Bounds(0, 2250, 1080, 2400)))
    }

    @Test
    fun `no captured screen has its navigation bar painted over`() {
        // The three apps publish an id for their bar. Wherever one of those is on screen,
        // the floor has to stop at it — whether or not the id is the thing that found it.
        val ids = listOf("tab_bar", "bottom_nav_container", "pivot_bar", "home_bottom_bar")
        // LinkedIn's outer bar view starts three pixels above the row of buttons it holds,
        // on a shadow. Three pixels of a shadow is not a bricked app.
        val slack = 4
        var checked = 0
        for (file in XmlUiNode.fixtures()) {
            val root = XmlUiNode.load(file)
            val bar = ids.firstNotNullOfOrNull { root.findById(it)?.bounds }
                ?.takeIf { !it.isEmpty } ?: continue
            checked += 1
            assertTrue(
                "${file.name}: floor ${ScreenChrome.of(root).floor} runs into bar $bar",
                ScreenChrome.of(root).floor <= bar.top + slack,
            )
        }
        assertTrue("expected most captures to show a navigation bar", checked >= 40)
    }
}

/**
 * The false positive that the shape test had to be tightened against, kept as its own
 * class because it came out of a real capture rather than out of reasoning.
 */
class NavBarLookalikeTest {

    @Test
    fun `Instagram's like row is not mistaken for the tab bar`() {
        // Captured from a device: row_feed_view_group_buttons sits flush above the real
        // tab bar, is full width, is 76px tall and holds seven controls in a row — every
        // test the first version applied. Its buttons are 64 to 183 pixels wide, though,
        // and a navigation bar divides its width evenly.
        val widths = listOf(32 to 106, 106 to 192, 192 to 298, 298 to 362, 362 to 545, 561 to 651, 940 to 1048)
        val likeRow = FakeNode(
            viewId = "com.instagram.android:id/row_feed_view_group_buttons",
            bounds = Bounds(0, 2135, 1080, 2211),
            children = widths.map { (left, right) -> FakeNode(bounds = Bounds(left, 2135, right, 2211)) },
        )
        val tabBar = FakeNode(
            viewId = "com.instagram.android:id/tab_bar",
            bounds = Bounds(0, 2211, 1080, 2337),
            children = (0 until 5).map { FakeNode(bounds = Bounds(it * 216, 2211, (it + 1) * 216, 2337)) },
        )
        val root = FakeNode(bounds = Bounds(0, 0, 1080, 2400), children = listOf(likeRow, tabBar))

        assertEquals(Bounds(0, 2211, 1080, 2337), ScreenChrome.of(root).navBar)
    }
}
