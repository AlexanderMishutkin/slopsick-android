package dev.amishutkin.focustube.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import dev.amishutkin.focustube.core.Bounds
import dev.amishutkin.focustube.core.Surface
import dev.amishutkin.focustube.core.TargetApp

/**
 * Draws the covers.
 *
 * An accessibility service cannot hide another app's views — it can only draw over them —
 * so a covered post is still there, still scrolls, and can still be tapped. The main
 * window is FLAG_NOT_TOUCHABLE so that every gesture reaches the app underneath:
 * scrolling and flinging must feel exactly as they did.
 *
 * That pass-through is wrong for one case. Painting over the Reels tab hides the button
 * but leaves it working, and a button you cannot see but can still hit is worse than one
 * you can see. Those regions get small windows of their own that do take touches, and
 * swallow them.
 */
class OverlayController(private val context: Context) {

    private companion object {
        const val TAG = "FocusTube"

        /**
         * Geometry only — never text taken from the feed. A log line is readable by any
         * app holding READ_LOGS, and this one reads other people's posts for a living.
         */
        val DEBUG = dev.amishutkin.focustube.BuildConfig.DEBUG

        /**
         * A cover should read as "nothing here", not as a hole punched in the app, so it
         * is painted the colour that app uses behind the thing being covered.
         *
         * The feeds follow the system theme, so that is what is checked at runtime — but
         * Reels is dark whatever the phone is set to, and a white sheet over it (and a
         * white rectangle where its tab button used to be) announces itself loudly.
         */
        const val INSTAGRAM_DARK = 0xFF000000.toInt()
        const val INSTAGRAM_LIGHT = 0xFFFFFFFF.toInt()
        const val LINKEDIN_DARK = 0xFF1B1F23.toInt()
        const val LINKEDIN_LIGHT = 0xFFF4F2EE.toInt()

        /**
         * Instagram's tab bar is not the same colour as the surface above it — measured
         * off a device, the bar is #0C1014 where the Reels player is pure black. Painting
         * the blocked Reels button in the surface colour leaves a visible rectangle
         * exactly where the thing you are trying to forget used to be.
         */
        const val INSTAGRAM_BAR_DARK = 0xFF0C1014.toInt()
    }

    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: CoverView? = null
    private var blockerViews = mutableListOf<Pair<Bounds, View>>()

    fun show(
        app: TargetApp?,
        surface: Surface,
        covers: List<Bounds>,
        blockers: List<Bounds>,
    ) {
        showCovers(colorFor(app, surface), covers)
        showBlockers(blockers, barColorFor(app, surface))
    }

    fun hide() {
        view?.let { runCatching { windows.removeView(it) } }
        view = null
        for ((_, blocker) in blockerViews) runCatching { windows.removeView(blocker) }
        blockerViews.clear()
    }

    private fun showCovers(color: Int, covers: List<Bounds>) {
        if (covers.isEmpty()) {
            view?.let { runCatching { windows.removeView(it) } }
            view = null
            return
        }
        val target = view ?: CoverView(context).also {
            val added = runCatching { windows.addView(it, coverParams()) }
            if (added.isFailure) {
                Log.w(TAG, "could not add overlay window", added.exceptionOrNull())
                return
            }
            view = it
        }
        target.setFill(color)
        target.setBands(covers)
    }

    private fun showBlockers(blockers: List<Bounds>, color: Int) {
        if (blockerViews.map { it.first } == blockers) {
            for ((_, blocker) in blockerViews) (blocker as BlockerView).setFill(color)
            return
        }

        for ((_, blocker) in blockerViews) runCatching { windows.removeView(blocker) }
        blockerViews.clear()

        for (bounds in blockers) {
            val blocker = BlockerView(context).apply { setFill(color) }
            val added = runCatching { windows.addView(blocker, blockerParams(bounds)) }
            if (added.isSuccess) blockerViews += bounds to blocker
        }
    }

    /** The colour of the bar a blocked control sits on, which is not the surface colour. */
    private fun barColorFor(app: TargetApp?, surface: Surface): Int {
        if (app != TargetApp.INSTAGRAM) return colorFor(app, surface)
        return if (surface == Surface.REELS || isNight()) INSTAGRAM_BAR_DARK else INSTAGRAM_LIGHT
    }

    private fun isNight(): Boolean =
        context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun colorFor(app: TargetApp?, surface: Surface): Int {
        // Reels is a dark surface on a light phone too, and the blocked tab button sits
        // on its black bar, so the theme does not get a say here.
        if (surface == Surface.REELS) return INSTAGRAM_DARK

        val night = isNight()
        return when (app) {
            TargetApp.LINKEDIN -> if (night) LINKEDIN_DARK else LINKEDIN_LIGHT
            else -> if (night) INSTAGRAM_DARK else INSTAGRAM_LIGHT
        }
    }

    @SuppressLint("WrongConstant")
    private fun coverParams() = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        gravity = Gravity.TOP or Gravity.START
    }

    @SuppressLint("WrongConstant")
    private fun blockerParams(bounds: Bounds) = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        format = PixelFormat.TRANSLUCENT
        // Deliberately *not* FLAG_NOT_TOUCHABLE: swallowing the tap is the whole point.
        // The window is exactly the size of the button so nothing else is affected.
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        gravity = Gravity.TOP or Gravity.START
        x = bounds.left
        y = bounds.top
        width = bounds.width
        height = bounds.height
    }

    /** Paints the bands, and nothing else. */
    private class CoverView(context: Context) : View(context) {

        private var bands: List<Bounds> = emptyList()
        private val fill = Paint().apply { isAntiAlias = false }

        fun setBands(next: List<Bounds>) {
            if (next == bands) return
            bands = next
            invalidate()
        }

        fun setFill(color: Int) {
            if (fill.color == color) return
            fill.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (DEBUG) {
                val (t, b) = systemBarInsets()
                Log.d(TAG, "draw ${bands.size} bands, view ${width}x$height, insets top=$t bottom=$b")
            }
            // The window is laid out edge to edge so that band coordinates line up with
            // the screen coordinates the accessibility tree reports. That also puts the
            // status and navigation bars inside it, and painting over the clock is both
            // ugly and not this app's business.
            val (topInset, bottomInset) = systemBarInsets()
            val floor = (height - bottomInset).toFloat()
            for (band in bands) {
                val top = maxOf(band.top, topInset).toFloat()
                val bottom = minOf(band.bottom.toFloat(), floor)
                if (bottom - top < 1f) continue
                canvas.drawRect(band.left.toFloat(), top, band.right.toFloat(), bottom, fill)
            }
        }

        /**
         * An accessibility overlay window reports no insets of its own — `rootWindowInsets`
         * comes back all zeros — so the bars have to be measured against the display
         * instead of against this view.
         */
        private fun systemBarInsets(): Pair<Int, Int> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = context.getSystemService(WindowManager::class.java)
                    ?.currentWindowMetrics
                if (metrics != null) {
                    val bars = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
                    if (bars.top > 0 || bars.bottom > 0) return bars.top to bars.bottom
                }
            }
            return statusBarHeight() to 0
        }

        @SuppressLint("DiscouragedApi", "InternalInsetResource")
        private fun statusBarHeight(): Int {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            return if (id > 0) resources.getDimensionPixelSize(id) else 0
        }
    }

    /**
     * Sits exactly over a control that should not be reachable: paints it out, and eats
     * the touch that the main cover would have let through.
     */
    @SuppressLint("ViewConstructor")
    private class BlockerView(context: Context) : View(context) {

        private val fill = Paint().apply { isAntiAlias = false }

        fun setFill(color: Int) {
            if (fill.color == color) return
            fill.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean = true
    }
}
