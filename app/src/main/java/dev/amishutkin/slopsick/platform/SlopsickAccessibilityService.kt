package dev.amishutkin.slopsick.platform

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import dev.amishutkin.slopsick.R
import dev.amishutkin.slopsick.core.Bounds
import dev.amishutkin.slopsick.core.ChromeAnalyzer
import dev.amishutkin.slopsick.core.FeedLedger
import dev.amishutkin.slopsick.core.FeedScan
import dev.amishutkin.slopsick.core.InstagramAnalyzer
import dev.amishutkin.slopsick.core.LinkedInAnalyzer
import dev.amishutkin.slopsick.core.OverlayPlan
import dev.amishutkin.slopsick.core.Settings
import dev.amishutkin.slopsick.core.Surface
import dev.amishutkin.slopsick.core.TargetApp
import dev.amishutkin.slopsick.core.UiNode
import dev.amishutkin.slopsick.core.Verdict
import dev.amishutkin.slopsick.core.YouTubeAnalyzer
import kotlin.math.abs

/**
 * Watches the four apps named in `accessibility_service_config.xml` and keeps the
 * overlay in step with what is on screen.
 *
 * The system only ever delivers events for those packages, so this service cannot see
 * the rest of the device even if it tried to.
 *
 * ## Where the time goes
 *
 * Reading an accessibility tree is a synchronous round trip into another process, and on
 * a loaded feed it costs tens of milliseconds — far too much to do once per frame while
 * a fling is in flight. The first version dealt with that by covering the whole feed the
 * moment anything scrolled and waiting for the next scan to cut the holes back, which is
 * why scrolling felt like it did: the post you were reading went black, and stayed black
 * for the best part of a second after you stopped.
 *
 * Three things fix it, and none of them is "scan more often":
 *
 *  1. **Scans happen on a worker thread.** The main thread does nothing but paint, so a
 *     scroll event is never queued behind a tree read.
 *  2. **Between scans the cover is projected, not guessed.** A scroll event carries the
 *     exact number of pixels the list moved, so the last scan's holes can be moved with
 *     it — see [OverlayPlan.project]. Covering everything is now only the fallback for
 *     when that distance is unknown or has accumulated past being trustworthy.
 *  3. **Stopping is what triggers a scan.** Events are not debounced on a fixed timer;
 *     the scan is scheduled for [QUIET_MS] after the last one and re-armed each time
 *     another arrives, so it lands as soon as the feed stops moving rather than at the
 *     end of a fixed wait. A long drag would starve it, so a scan is forced anyway if
 *     none has finished for [MAX_STALE_MS].
 */
class SlopsickAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())

    /** Tree reads and analysis; never touches a view. */
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler

    private lateinit var overlay: OverlayController
    private lateinit var store: SettingsStore
    private lateinit var reporter: BugReporter

    @Volatile
    private var settings: Settings = Settings()
    private var settingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private var ledger = FeedLedger()
    private var currentApp: TargetApp? = null

    /** The last completed scan, and what it is worth as the feed moves away from it. */
    private var lastScan: FeedScan? = null
    private var driftSinceScan = 0

    /** Kept so the blocker windows are not torn down and rebuilt on every scroll. */
    private var lastBlockers: List<Bounds> = emptyList()

    /**
     * The last place each app's navigation bar was seen, and the row below which nothing
     * may be painted because of it.
     *
     * A frame is allowed to be wrong about the bar — LinkedIn reports its own collapsed
     * to zero height at the bottom of the screen while it is plainly on screen and being
     * tapped — but it is not allowed to move this down. Only a frame that actually finds
     * a bar sets it, and it is dropped when the app is left, not while inside one.
     */
    private val navBars = HashMap<TargetApp, Pair<Bounds, Bounds>>()

    private fun paintFloor(app: TargetApp, scan: FeedScan?): Int {
        val screen = scan?.safe
        scan?.navBar?.takeIf { !it.isEmpty && screen != null }
            ?.let { navBars[app] = it to screen!! }
        // A frame that positively established there is no bar clears the memory: that is
        // YouTube scrolling its own away, and the space really is feed. A frame that only
        // failed to find one changes nothing.
        if (scan?.barless == true) navBars.remove(app)
        // Only usable while the screen is the shape it was measured on: a bar remembered
        // in portrait is in the wrong place the moment the phone is turned.
        val remembered = navBars[app]
            ?.takeIf { (_, on) -> screen == null || on.width == screen.width }
            ?.first?.top
        val claimed = scan?.safe?.bottom
        return when {
            remembered != null && claimed != null -> minOf(remembered, claimed)
            else -> remembered ?: claimed ?: Int.MAX_VALUE
        }
    }

    /** When the feed last moved, and when a scan last finished. */
    private var lastScrollAt = 0L
    private var lastScanAt = 0L

    /** The page Chrome last showed in its address bar, while Chrome stays in front. */
    private var chromePage: ChromeAnalyzer.Page = ChromeAnalyzer.Page.OTHER

    private var scanning = false
    private var scanQueued = false

    /** Retries after entering an app, while the feed is still being built. */
    private var settleStep = 0

    private val scanTick = Runnable { scan() }
    private val settleTick = object : Runnable {
        override fun run() {
            scan()
            if (settleStep < SETTLE_LADDER.size) {
                main.postDelayed(this, SETTLE_LADDER[settleStep++])
            }
        }
    }

    /**
     * Events only arrive for the packages named in the service config, which is what
     * keeps this service blind to the rest of the device — but it also means leaving one
     * of those apps produces no event at all, and without this the cover would stay on
     * screen over the launcher, over other apps, over its own settings.
     *
     * So while anything is being covered, and only then, the foreground package is
     * polled. Nothing but the package name is read.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            val root = rootInActiveWindow
            val front = root?.packageName?.toString()
            root?.recycleCompat()
            when {
                front == null -> {
                    // Transiently unreadable inside the target app, or a window that is
                    // not ours to see — which is what the launcher looks like from in
                    // here. Take the cover down on the first sign of it and only decide
                    // we have left after [MAX_MISSES]: inside the app the next scan
                    // repaints within a couple of hundred milliseconds, and a moment
                    // uncovered there is a far smaller thing than a white rectangle
                    // sitting on somebody's home screen.
                    misses += 1
                    overlay.hide()
                    if (misses >= MAX_MISSES) return clear()
                }
                TargetApp.of(front) != currentApp -> return clear()
                else -> misses = 0
            }
            main.postDelayed(this, if (currentApp == TargetApp.CHROME) CHROME_WATCHDOG_MS else WATCHDOG_MS)
        }
    }

    private var misses = 0

    /** Consecutive scans that could not read the window at all. */
    private var unreadable = 0

    /** Consecutive scans that recognised nothing on a feed that had been recognised. */
    private var blindFrames = 0

    /**
     * Whether this scan has lost sight of a feed the last one could read, without the
     * screen having moved. Only ever true once in a row: [blindFrames] makes the second
     * such frame authoritative, so a feed that genuinely emptied is still covered.
     */
    private fun blind(next: FeedScan): Boolean {
        if (blindFrames > 0) return false
        val previous = lastScan ?: return false
        if (previous.surface != next.surface || next.surface != Surface.FEED) return false
        if (SystemClock.uptimeMillis() - lastScrollAt < SETTLE_MS) return false
        if (previous.feedBounds != next.feedBounds) return false
        val knewSomething = previous.items.any { it.verdict != Verdict.UNKNOWN }
        val knowsNothing = next.items.none { it.verdict != Verdict.UNKNOWN }
        return knewSomething && knowsNothing
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        thread = HandlerThread("slopsick-scan").apply { start() }
        worker = Handler(thread.looper)
        overlay = OverlayController(this)
        store = SettingsStore(this)
        reporter = BugReporter(this)
        settings = store.load()
        overlay.onReport = { region -> report(region) }
        settingsListener = store.observe {
            settings = store.load()
            main.post { scan() }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val app = TargetApp.of(event?.packageName?.toString())
        if (app == null) {
            clear()
            return
        }
        if (app != currentApp) return enter(app)

        val now = SystemClock.uptimeMillis()
        val scrolled = event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        if (scrolled) {
            lastScrollAt = now
            paintProjected(app, scrollDeltaOf(event))
        }

        main.removeCallbacks(scanTick)
        main.postDelayed(scanTick, delayFor(app, scrolled, now))
    }

    /**
     * When to scan next.
     *
     * A scroll is answered as soon as the feed goes quiet, because that is the moment the
     * projection stops being able to keep up. Everything else is throttled: an autoplaying
     * video emits content-changed events by the frame, and none of them move a post, so
     * answering each one is a tree read per frame for no change in what is painted.
     */
    private fun delayFor(app: TargetApp, scrolled: Boolean, now: Long): Long {
        val quiet = quietFor(app)
        if (scrolled) return if (now - lastScanAt > MAX_STALE_MS) 0L else quiet
        return maxOf(quiet, MIN_SCAN_GAP_MS - (now - lastScanAt))
    }

    /** A different app is a different feed; nothing remembered about the last one holds. */
    private fun enter(app: TargetApp) {
        currentApp = app
        ledger = FeedLedger()
        lastScan = null
        driftSinceScan = 0
        chromePage = ChromeAnalyzer.Page.OTHER
        overlay.hide()

        // A feed is rarely finished rendering when the window-state event arrives, and
        // once it has settled no further events need come — so an app opened and left
        // alone would sit there uncovered until something moved. Rather than poll
        // forever, try a handful of times over the first few seconds and stop.
        settleStep = 0
        main.removeCallbacks(settleTick)
        main.post(settleTick)
    }

    /**
     * Repaints from the last scan and the distance scrolled since, without reading the
     * tree. Falls back to covering the whole feed when the distance is unknown.
     */
    private fun paintProjected(app: TargetApp, dy: Int?) {
        val scan = lastScan ?: return
        if (dy != null) driftSinceScan += abs(dy)
        // The feed, and only the feed. An earlier version fell back to covering the whole
        // safe region here, which on a frame that had got the navigation bar wrong meant
        // a white sheet over the entire app — the "random white polygons" this was
        // reported as. There is nothing to be gained from covering more than the feed:
        // everything outside it was never ours to paint.
        val projected = dy?.let { OverlayPlan.project(scan, it, driftSinceScan) }
            ?: scan.feedBounds?.let { listOf(it) }
            ?: scan.blackouts.takeIf { it.isNotEmpty() }
            ?: return
        overlay.show(app, scan.surface, projected, emptyList(), lastBlockers, paintFloor(app, scan))
        // A button anchored to where a band was a frame ago is worse than no button.
        overlay.setReports(emptyList())
        // Painting without arming the watchdog is how a cover outlives the app it was
        // drawn over: nothing else ever comes back to take it down.
        armWatchdog(true)
    }

    /**
     * How far the list moved, from the event itself. Not every view reports it — Chrome
     * does not — and a value larger than the screen is a jump rather than a scroll, so
     * either way the caller is told nothing rather than something wrong.
     */
    private fun scrollDeltaOf(event: AccessibilityEvent): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val dy = event.scrollDeltaY
        if (dy == 0 || dy == UNSET_DELTA) return null
        val limit = lastScan?.safe?.height ?: lastScan?.feedBounds?.height ?: return null
        return dy.takeIf { abs(it) <= limit }
    }

    /**
     * How long after the last event to scan. Chrome has to build an accessibility tree
     * for an entire web page on every read, and asking too often visibly hurts it; the
     * native feeds are cheap by comparison and want the responsiveness.
     */
    private fun quietFor(app: TargetApp) = if (app == TargetApp.CHROME) CHROME_QUIET_MS else QUIET_MS

    override fun onInterrupt() = clear()

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        clear()
        settingsListener?.let { store.stopObserving(it) }
        if (::thread.isInitialized) thread.quitSafely()
        return super.onUnbind(intent)
    }

    // --- scanning ------------------------------------------------------------------

    private class Reading(val root: UiNode?, val front: TargetApp?, val url: String?)

    private fun scan() {
        val app = currentApp ?: return clear()
        if (scanning) {
            scanQueued = true
            return
        }
        scanning = true
        val snapshotSettings = settings
        val startedAt = SystemClock.uptimeMillis()
        worker.post {
            val reading = read(app)
            main.post { finish(app, reading, snapshotSettings, startedAt) }
        }
    }

    /** Runs on the worker: the tree read and nothing that touches a window. */
    private fun read(app: TargetApp): Reading {
        val live = rootInActiveWindow
        val front = TargetApp.of(live?.packageName?.toString())
        val root = SnapshotNode.of(live)
        live?.recycleCompat()
        if (root != null) TreeDebug.dump(root)
        val url = if (app == TargetApp.CHROME && root != null) ChromeAnalyzer.url(root) else null
        return Reading(root, front, url)
    }

    private fun finish(app: TargetApp, reading: Reading, used: Settings, startedAt: Long) {
        scanning = false
        if (currentApp != app) return
        if (reading.front != null && reading.front != app) return clear()

        if (reading.root == null) {
            // The window can be momentarily unreadable — mid-transition, or while the app
            // is busy. Leaving it here would strand whatever is on screen under the last
            // projection, so try again shortly rather than settling for a stale overlay.
            //
            // Bounded, though. An unreadable window is also what leaving the app looks
            // like from in here, and an unbounded retry loop is a cover that never comes
            // down. Past [MAX_MISSES] the honest answer is that we are not in the app any
            // more, whatever the last event said.
            unreadable += 1
            if (unreadable > MAX_MISSES) return clear()
            main.removeCallbacks(scanTick)
            main.postDelayed(scanTick, RETRY_MS)
            return
        }
        unreadable = 0

        // Chrome's address bar disappears on scroll, taking the only evidence of which
        // page this is with it. Remember it for as long as Chrome is in front.
        reading.url?.let { chromePage = ChromeAnalyzer.pageOf(it) }

        val scan = when (app) {
            TargetApp.INSTAGRAM -> InstagramAnalyzer.analyze(reading.root, used)
            TargetApp.LINKEDIN -> LinkedInAnalyzer.analyze(reading.root, used)
            TargetApp.YOUTUBE -> YouTubeAnalyzer.analyze(reading.root, used)
            TargetApp.CHROME -> ChromeAnalyzer.analyze(reading.root, used, chromePage)
        }

        lastScanAt = SystemClock.uptimeMillis()
        lastTree = reading.root

        if (scan.isEmpty) {
            // Not a screen this app has an opinion about — a profile, a chat, settings.
            lastScan = null
            lastBlockers = emptyList()
            overlay.hide()
            armWatchdog(false)
            requeue()
            return
        }

        // Reels has no posts to weigh, so there is nothing for the ledger to remember and
        // running it would only leave stale verdicts behind for the feed.
        val tracked = if (scan.surface == Surface.FEED) ledger.observe(scan) else scan

        // A frame that has suddenly stopped recognising a feed it recognised a moment ago,
        // on a screen that has not moved, is far more likely to be a half-built tree than
        // a feed that really changed — an app mid-relayout, or a video swapping surfaces.
        // Believing it means covering the whole feed for one frame and uncovering it on
        // the next, which is what "it blinks white" is. So it is given one chance to say
        // the same thing twice; only a second frame agreeing is taken seriously.
        if (blind(tracked)) {
            blindFrames += 1
            main.removeCallbacks(scanTick)
            main.postDelayed(scanTick, RETRY_MS)
            return
        }
        blindFrames = 0
        lastScan = tracked
        driftSinceScan = 0

        val bands = OverlayPlan.cover(tracked)
        val blockers = OverlayPlan.block(tracked)

        // Outlines and labels only once the feed has stopped moving: mid-scroll the
        // bounds are already a frame behind and a border in the wrong place is more
        // distracting than a plain sheet.
        val settled = SystemClock.uptimeMillis() - lastScrollAt > SETTLE_MS
        if (!settled) {
            main.removeCallbacks(scanTick)
            main.postDelayed(scanTick, SETTLE_MS)
        }
        val details = if (settled) OverlayPlan.details(tracked) else emptyList()
        val painted = if (settled) bands else bands.map { OverlayPlan.grown(it, tracked) }

        if (dev.amishutkin.slopsick.BuildConfig.DEBUG) {
            android.util.Log.d(
                "Slopsick",
                "$app ${tracked.surface} feed=${tracked.feedBounds} " +
                    "items=${tracked.items.size} bands=${bands.size} blockers=${blockers.size} " +
                    "read=${lastScanAt - startedAt}ms sinceScroll=" +
                    "${lastScanAt - lastScrollAt}ms settled=$settled",
            )
        }
        lastBlockers = blockers
        overlay.show(app, tracked.surface, painted, details, blockers, paintFloor(app, tracked))
        overlay.setReports(if (settled && used.reportButtons) bands else emptyList())
        armWatchdog(bands.isNotEmpty() || blockers.isNotEmpty())
        requeue()
    }

    /**
     * Events that arrived while a scan was in flight are answered by one more scan —
     * under the same throttle as any other event, or a busy window would have this
     * scanning back to back through [scanQueued] and never reach the floor.
     */
    private fun requeue() {
        if (!scanQueued) return
        scanQueued = false
        val app = currentApp ?: return
        main.removeCallbacks(scanTick)
        main.postDelayed(scanTick, delayFor(app, scrolled = false, now = SystemClock.uptimeMillis()))
    }

    // --- bug reports ---------------------------------------------------------------

    /** The tree the last scan was made from, for a report to write out. */
    private var lastTree: UiNode? = null

    /**
     * Writes a report for the region whose button was tapped.
     *
     * The screenshot is taken with the covers **up**. An earlier version took them down
     * to show what was underneath, which made a better picture and a worse tool: the
     * reports are about where the rectangles landed, and a folder of screenshots of the
     * feed is the one thing this app exists to stop you looking at. What comes down for
     * the shutter is the buttons, one of which sits on the corner of the outline you are
     * most likely to be complaining about.
     */
    private fun report(region: Bounds) {
        val app = currentApp ?: return
        if (reporting) return
        reporting = true
        val tree = lastTree
        val scan = lastScan
        val used = settings
        overlay.setReportsVisible(false)
        main.postDelayed({
            reporter.capture(this, app, region, tree, scan, used) { dir ->
                main.post {
                    overlay.setReportsVisible(true)
                    reporting = false
                    val name = dir?.name
                    android.widget.Toast.makeText(
                        this,
                        if (name != null) getString(R.string.report_saved, name)
                        else getString(R.string.report_failed),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }, SHUTTER_MS)
    }

    private var reporting = false

    // --- lifecycle -----------------------------------------------------------------

    private fun armWatchdog(active: Boolean) {
        main.removeCallbacks(watchdog)
        misses = 0
        if (active) main.postDelayed(watchdog, WATCHDOG_MS)
    }

    private fun clear() {
        main.removeCallbacks(scanTick)
        main.removeCallbacks(settleTick)
        main.removeCallbacks(watchdog)
        misses = 0
        unreadable = 0
        blindFrames = 0
        navBars.clear()
        currentApp = null
        lastScan = null
        lastTree = null
        driftSinceScan = 0
        lastBlockers = emptyList()
        chromePage = ChromeAnalyzer.Page.OTHER
        if (::overlay.isInitialized) overlay.hide()
    }

    private companion object {
        /**
         * How long the screen has to be still before a scan runs. Short, because it is
         * re-armed on every event: this is "the moment things stop", not a fixed wait.
         */
        const val QUIET_MS = 70L
        const val CHROME_QUIET_MS = 300L

        /** A long slow drag never goes quiet, so scan anyway once a scan is this old. */
        const val MAX_STALE_MS = 400L

        /**
         * The fastest anything other than a scroll may cause a scan. A video playing in
         * the feed changes its window several times a second and moves nothing.
         */
        const val MIN_SCAN_GAP_MS = 250L

        /** How long to wait before re-reading a window that could not be read. */
        const val RETRY_MS = 200L

        /** How often to check we are still in the app we are covering. */
        const val WATCHDOG_MS = 250L

        /** Each poll is another root read; Chrome cannot afford them as often. */
        const val CHROME_WATCHDOG_MS = 900L

        /**
         * How still the feed has to be before the outlines and labels go on. It only has
         * to outlast one frame of a fling, and a scan scheduled [QUIET_MS] after the last
         * scroll normally lands past it already — so the labels appear in the same pass
         * that cuts the holes, rather than a round trip later.
         */
        const val SETTLE_MS = 90L

        /** Rescans after entering an app, while the feed is still being built. */
        val SETTLE_LADDER = longArrayOf(150L, 350L, 700L, 1200L, 2000L)

        /** Unreadable this many times in a row means the app is gone, not just busy. */
        const val MAX_MISSES = 3

        /** What [AccessibilityEvent.getScrollDeltaY] returns when the view did not set it. */
        const val UNSET_DELTA = -1

        /** How long the report buttons stay down before the screenshot is taken. */
        const val SHUTTER_MS = 80L
    }
}
