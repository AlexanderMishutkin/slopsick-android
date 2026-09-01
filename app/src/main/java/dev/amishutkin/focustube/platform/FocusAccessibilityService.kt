package dev.amishutkin.focustube.platform

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import dev.amishutkin.focustube.core.Bounds
import dev.amishutkin.focustube.core.FeedLedger
import dev.amishutkin.focustube.core.InstagramAnalyzer
import dev.amishutkin.focustube.core.LinkedInAnalyzer
import dev.amishutkin.focustube.core.OverlayPlan
import dev.amishutkin.focustube.core.Settings
import dev.amishutkin.focustube.core.Surface
import dev.amishutkin.focustube.core.TargetApp
import dev.amishutkin.focustube.core.YouTubeAnalyzer

/**
 * Watches the three apps named in `accessibility_service_config.xml` and keeps the
 * overlay in step with what is on screen.
 *
 * The system only ever delivers events for those three packages, so this service cannot
 * see the rest of the device even if it tried to.
 */
class FocusAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var overlay: OverlayController
    private lateinit var store: SettingsStore

    private var settings: Settings = Settings()
    private var settingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private var ledger = FeedLedger()
    private var currentApp: TargetApp? = null

    /** The feed region from the last successful scan, used to cover instantly on scroll. */
    private var lastFeedBounds: Bounds? = null

    /** Kept across the scroll fail-safe so the blocker windows are not torn down and
     *  rebuilt on every scroll event. */
    private var lastBlockers: List<Bounds> = emptyList()

    /** When the feed last moved, so the labels can wait for it to stop. */
    private var lastScrollAt: Long = 0L

    private val refresh = Runnable { update() }

    /**
     * Events only arrive for the three packages named in the service config, which is
     * what keeps this service blind to the rest of the device — but it also means
     * leaving one of those apps produces no event at all, and without this the cover
     * would stay on screen over the launcher, over other apps, over its own settings.
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
                    // not ours to see. Tolerate a moment of it, then assume we have left.
                    misses += 1
                    if (misses >= MAX_MISSES) return clear()
                }
                TargetApp.of(front) != currentApp -> return clear()
                else -> misses = 0
            }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private var misses = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(this)
        store = SettingsStore(this)
        settings = store.load()
        settingsListener = store.observe {
            settings = store.load()
            handler.post(refresh)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val app = TargetApp.of(event?.packageName?.toString())
        if (app == null) {
            clear()
            return
        }
        if (app != currentApp) {
            currentApp = app
            // A different app is a different feed; nothing remembered about the last one
            // tells us anything about this one.
            ledger = FeedLedger()
            lastFeedBounds = null
            overlay.hide()
        }

        // Scrolling invalidates every hole at once: the post that earned one has moved,
        // and until the next scan says otherwise the honest thing to show is a cover.
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            lastScrollAt = SystemClock.uptimeMillis()
            lastFeedBounds?.let {
                overlay.show(app, Surface.FEED, listOf(it), emptyList(), lastBlockers)
            }
        }

        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, DEBOUNCE_MS)
    }

    override fun onInterrupt() = clear()

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        clear()
        settingsListener?.let { store.stopObserving(it) }
        return super.onUnbind(intent)
    }

    private fun update() {
        val app = currentApp ?: return clear()
        val live = rootInActiveWindow
        if (live != null && TargetApp.of(live.packageName?.toString()) != app) {
            // The foreground moved on between the event and this pass.
            return clear()
        }
        val root = SnapshotNode.of(live)
        live?.recycleCompat()
        if (root != null) TreeDebug.dump(root)
        if (root == null) {
            // The window can be momentarily unreadable — mid-transition, or while the app
            // is busy. Leaving it here would strand whatever is on screen under the last
            // set of bands, including the blanket cover a scroll puts up, so try again
            // shortly rather than settling for a stale overlay.
            handler.removeCallbacks(refresh)
            handler.postDelayed(refresh, RETRY_MS)
            return
        }

        val scan = when (app) {
            TargetApp.INSTAGRAM -> InstagramAnalyzer.analyze(root, settings)
            TargetApp.LINKEDIN -> LinkedInAnalyzer.analyze(root, settings)
            TargetApp.YOUTUBE -> YouTubeAnalyzer.analyze(root, settings)
        }

        if (scan.isEmpty) {
            // Not a screen this app has an opinion about — a profile, a chat, settings.
            lastFeedBounds = null
            lastBlockers = emptyList()
            overlay.hide()
            armWatchdog(false)
            return
        }

        // Reels has no posts to weigh, so there is nothing for the ledger to remember and
        // running it would only leave stale verdicts behind for the feed.
        val tracked = if (scan.surface == Surface.FEED) ledger.observe(scan) else scan
        lastFeedBounds = tracked.feedBounds
        val bands = OverlayPlan.cover(tracked)
        val blockers = OverlayPlan.block(tracked)

        // Outlines and labels only once the feed has stopped moving: mid-scroll the bounds
        // are already stale, and a border in the wrong place is more distracting than a
        // plain sheet. A scroll therefore schedules one more pass to draw them.
        val settled = SystemClock.uptimeMillis() - lastScrollAt > SETTLE_MS
        if (!settled) {
            handler.removeCallbacks(refresh)
            handler.postDelayed(refresh, SETTLE_MS)
        }
        val details = if (settled) OverlayPlan.details(tracked) else emptyList()
        if (dev.amishutkin.focustube.BuildConfig.DEBUG) {
            android.util.Log.d(
                "FocusTube",
                "$app ${tracked.surface} feed=${tracked.feedBounds} " +
                    "items=${tracked.items.size} bands=${bands.size} blockers=${blockers.size}",
            )
        }
        lastBlockers = blockers
        overlay.show(app, tracked.surface, bands, details, blockers)
        armWatchdog(bands.isNotEmpty() || blockers.isNotEmpty())
    }

    private fun armWatchdog(active: Boolean) {
        handler.removeCallbacks(watchdog)
        misses = 0
        if (active) handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    private fun clear() {
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(watchdog)
        misses = 0
        currentApp = null
        lastFeedBounds = null
        lastBlockers = emptyList()
        if (::overlay.isInitialized) overlay.hide()
    }

    private companion object {
        /**
         * Long enough that a fling does not trigger a scan per frame, short enough that
         * the feed does not sit under a blanket cover after it settles.
         */
        const val DEBOUNCE_MS = 120L

        /** How long to wait before re-reading a window that could not be read. */
        const val RETRY_MS = 250L

        /** How often to check we are still in the app we are covering. */
        const val WATCHDOG_MS = 400L

        /** How long after the last scroll the outlines and labels are drawn. */
        const val SETTLE_MS = 350L

        /** Unreadable this many times in a row means the app is gone, not just busy. */
        const val MAX_MISSES = 3
    }
}
