package dev.amishutkin.slopsick.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each app's rules belong to that app and nowhere else.
 *
 * At runtime this is settled by dispatch: the service reads the event's package name and
 * calls exactly one analyzer. That is one `when` in a file no JVM test can reach, which
 * is a thin thing to rest on — so these tests come at the same question from the other
 * side and check that the rules could not misfire even if they were pointed at the wrong
 * tree. YouTube's are checked hardest, because its shape rule ("two or three tall tiles
 * side by side is a Shorts shelf") is the one written without any id to anchor it, and a
 * grid of thumbnails is not a rare thing to find in another app.
 */
class AppScopeTest {

    private fun foreign() = XmlUiNode.fixtures().filterNot { it.name.startsWith("yt") }

    @Test
    fun `only YouTube's own package selects the YouTube analyzer`() {
        assertEquals(TargetApp.YOUTUBE, TargetApp.of("com.google.android.youtube"))
        assertEquals(TargetApp.INSTAGRAM, TargetApp.of("com.instagram.android"))
        assertEquals(TargetApp.LINKEDIN, TargetApp.of("com.linkedin.android"))
        assertEquals(TargetApp.CHROME, TargetApp.of("com.android.chrome"))
        // Neither a look-alike package nor a missing one is YouTube.
        assertNull(TargetApp.of("com.google.android.youtube.tv"))
        assertNull(TargetApp.of("com.google.android.apps.youtube.music"))
        assertNull(TargetApp.of("org.mozilla.firefox"))
        assertNull(TargetApp.of(null))
    }

    @Test
    fun `the YouTube rules find nothing in any other app's screen`() {
        for (file in foreign()) {
            val scan = YouTubeAnalyzer.analyze(XmlUiNode.load(file))
            assertTrue("${file.name}: YouTube found a feed in it", !scan.hasFeed)
            assertTrue("${file.name}: YouTube covered something", OverlayPlan.cover(scan).isEmpty())
            assertTrue("${file.name}: YouTube blocked a button", scan.blockers.isEmpty())
            assertTrue("${file.name}: YouTube blacked something out", scan.blackouts.isEmpty())
        }
    }

    @Test
    fun `the other analyzers find nothing in a YouTube screen`() {
        for (file in XmlUiNode.fixtures().filter { it.name.startsWith("yt") }) {
            val root = XmlUiNode.load(file)
            for ((name, scan) in listOf(
                "Instagram" to InstagramAnalyzer.analyze(root),
                "LinkedIn" to LinkedInAnalyzer.analyze(root),
                "Chrome" to ChromeAnalyzer.analyze(root),
            )) {
                assertTrue("${file.name}: $name found a feed in it", !scan.hasFeed)
                assertTrue("${file.name}: $name covered something", OverlayPlan.cover(scan).isEmpty())
                assertTrue("${file.name}: $name blocked a button", scan.blockers.isEmpty())
            }
        }
    }

    @Test
    fun `the Shorts switch changes nothing outside YouTube`() {
        for (file in foreign()) {
            val root = XmlUiNode.load(file)
            val on = analyze(file, root, Settings(hideShorts = true))
            val off = analyze(file, root, Settings(hideShorts = false))
            assertEquals("${file.name}: the Shorts switch moved a cover", on, off)
        }
    }

    /** Each capture through the analyzer that owns it, exactly as the service dispatches. */
    private fun analyze(file: java.io.File, root: UiNode, settings: Settings): List<Bounds> {
        val scan = when {
            file.name.startsWith("chrome") -> ChromeAnalyzer.analyze(root, settings)
            file.name.startsWith("li") -> LinkedInAnalyzer.analyze(root, settings)
            else -> InstagramAnalyzer.analyze(root, settings)
        }
        return OverlayPlan.cover(scan) + scan.blockers
    }
}
