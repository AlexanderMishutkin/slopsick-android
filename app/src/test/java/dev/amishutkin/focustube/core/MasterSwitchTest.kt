package dev.amishutkin.focustube.core

import org.junit.Assert.assertTrue
import org.junit.Test

/** Each app's master switch, off, means that app is not touched at all. */
class MasterSwitchTest {

    @Test
    fun `instagram off leaves the app alone`() {
        val off = Settings(instagram = false)
        assertTrue(InstagramAnalyzer.analyze(XmlUiNode.fixture("igscroll-01.xml"), off).isEmpty)
        assertTrue(InstagramAnalyzer.analyze(instagramReelsScreen(), off).isEmpty)
        assertTrue(InstagramAnalyzer.analyze(instagramExploreScreen(), off).isEmpty)
    }

    @Test
    fun `instagram off also covers the browser, which is the same feed`() {
        val off = Settings(instagram = false)
        assertTrue(ChromeAnalyzer.analyze(XmlUiNode.fixture("chromeig-01.xml"), off).isEmpty)
        assertTrue(ChromeAnalyzer.analyze(XmlUiNode.fixture("chromeweb-reels.xml"), off).isEmpty)
    }

    @Test
    fun `linkedin off leaves the app alone`() {
        val off = Settings(linkedIn = false)
        assertTrue(LinkedInAnalyzer.analyze(XmlUiNode.fixture("liscroll-01.xml"), off).isEmpty)
    }

    @Test
    fun `youtube off leaves the app alone, tab and all`() {
        val off = Settings(youtube = false)
        assertTrue(YouTubeAnalyzer.analyze(youtubeShortsPlayer(), off).isEmpty)
        assertTrue(YouTubeAnalyzer.analyze(youtubeHome(listOf("Shorts")), off).isEmpty)
    }

    @Test
    fun `one app off does not disturb another`() {
        val settings = Settings(linkedIn = false)
        assertTrue(InstagramAnalyzer.analyze(XmlUiNode.fixture("igscroll-01.xml"), settings).hasFeed)
    }
}
