package dev.amishutkin.slopsick.core

import org.junit.Test

/**
 * Not an assertion — a listing. Run it to see what the analyzers make of every captured
 * screen at once, which is how the expectations in the real tests were derived.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*FixtureReport*' -i
 */
class FixtureReport {

    @Test
    fun report() {
        var run = ""
        var ledger = FeedLedger()
        for (file in XmlUiNode.fixtures()) {
            val thisRun = file.name.substringBeforeLast('-')
            if (thisRun != run) {
                run = thisRun
                ledger = FeedLedger()
                println("--- $run ---")
            }
            val root = XmlUiNode.load(file)
            val scan = when {
                file.name.startsWith("ig") -> InstagramAnalyzer.analyze(root)
                file.name.startsWith("chrome") -> ChromeAnalyzer.analyze(root)
                file.name.startsWith("li") -> analyzeEither(root)
                else -> continue
            }
            val tracked = ledger.observe(scan)
            val covers = OverlayPlan.cover(tracked)
            println(
                "%-22s %-10s feed=%s items=%d keep=%d hide=%d unknown=%d bands=%d".format(
                    file.name,
                    scan.app,
                    scan.feedBounds?.let { "${it.top}..${it.bottom}" } ?: "-",
                    tracked.items.size,
                    tracked.items.count { it.verdict == Verdict.KEEP },
                    tracked.items.count { it.verdict == Verdict.HIDE },
                    tracked.items.count { it.verdict == Verdict.UNKNOWN },
                    covers.size,
                )
            )
            for (item in tracked.items) {
                println(
                    "    %-8s %-18s %4d..%-4d %s".format(
                        item.verdict, item.reason, item.bounds.top, item.bounds.bottom,
                        item.author ?: "",
                    )
                )
            }
        }
    }

    /** The LinkedIn captures include a few frames where LinkedIn had dropped to the background. */
    private fun analyzeEither(root: UiNode): FeedScan {
        val li = LinkedInAnalyzer.analyze(root)
        return if (li.hasFeed) li else InstagramAnalyzer.analyze(root)
    }
}
