package dev.amishutkin.slopsick.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.util.TypedValue
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import dev.amishutkin.slopsick.core.Bounds
import dev.amishutkin.slopsick.core.FeedItem
import dev.amishutkin.slopsick.core.Reason
import dev.amishutkin.slopsick.core.Surface
import dev.amishutkin.slopsick.R
import dev.amishutkin.slopsick.core.TargetApp

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
        const val TAG = "Slopsick"

        /**
         * Geometry only — never text taken from the feed. A log line is readable by any
         * app holding READ_LOGS, and this one reads other people's posts for a living.
         */
        val DEBUG = dev.amishutkin.slopsick.BuildConfig.DEBUG

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
        const val YOUTUBE_DARK = 0xFF0F0F0F.toInt()
        const val YOUTUBE_LIGHT = 0xFFFFFFFF.toInt()

        /** Report button size and how far it is inset into its region, in dp. */
        const val REPORT_SIZE = 40f
        const val REPORT_INSET = 8f

        /** Regions shorter than this get no button; there is nowhere to put it. */
        const val MIN_REPORTABLE = 56f
    }

    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: CoverView? = null
    private var blockerViews = mutableListOf<Pair<Bounds, View>>()
    private var reportViews = mutableListOf<Pair<Bounds, ReportView>>()

    /** Called with the covered region whose report button was tapped. */
    var onReport: ((Bounds) -> Unit)? = null

    fun show(
        app: TargetApp?,
        surface: Surface,
        covers: List<Bounds>,
        details: List<FeedItem>,
        blockers: List<Bounds>,
    ) {
        showCovers(colorFor(app, surface), covers, details)
        showBlockers(blockers, barColorFor(app, surface))
    }

    fun hide() {
        view?.let { runCatching { windows.removeView(it) } }
        view = null
        for ((_, blocker) in blockerViews) runCatching { windows.removeView(blocker) }
        blockerViews.clear()
        setReports(emptyList())
    }

    /**
     * Everything drawn, made invisible for a moment.
     *
     * A screenshot taken for a bug report has to show what is *underneath* the cover —
     * a picture of our own rectangles would say nothing that report.json does not
     * already say. The windows stay in place, so nothing has to be rebuilt afterwards.
     */
    fun setPainting(on: Boolean) {
        val visibility = if (on) View.VISIBLE else View.INVISIBLE
        view?.visibility = visibility
        for ((_, blocker) in blockerViews) blocker.visibility = visibility
        for ((_, button) in reportViews) button.visibility = visibility
    }

    /**
     * Puts a report button on each covered region, or takes them all away.
     *
     * The windows are reused rather than rebuilt: these move on every scan, and adding
     * and removing a handful of windows several times a second is exactly the kind of
     * work that made scrolling feel slow in the first place.
     */
    fun setReports(regions: List<Bounds>) {
        val wanted = regions.filter { it.height >= dp(MIN_REPORTABLE) }
        while (reportViews.size > wanted.size) {
            val (_, button) = reportViews.removeAt(reportViews.size - 1)
            runCatching { windows.removeView(button) }
        }
        for ((index, region) in wanted.withIndex()) {
            val params = reportParams(region)
            if (index < reportViews.size) {
                val (previous, button) = reportViews[index]
                button.region = region
                if (previous != region) {
                    runCatching { windows.updateViewLayout(button, params) }
                    reportViews[index] = region to button
                }
            } else {
                val button = ReportView(context).apply {
                    this.region = region
                    setOnClick { tapped -> onReport?.invoke(tapped) }
                }
                val added = runCatching { windows.addView(button, params) }
                if (added.isSuccess) reportViews += region to button
            }
        }
    }

    private fun dp(value: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics,
    ).toInt()

    private fun showCovers(color: Int, covers: List<Bounds>, details: List<FeedItem>) {
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
        target.setBands(covers, details)
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

    /**
     * The colour of the bar a blocked control sits on, which is not the surface colour.
     * Both apps use a lighter black for the navigation bar than for the video behind it,
     * and a black rectangle on a #0F0F0F bar is perfectly visible.
     */
    private fun barColorFor(app: TargetApp?, surface: Surface): Int = when (app) {
        TargetApp.INSTAGRAM ->
            if (surface == Surface.REELS || isNight()) INSTAGRAM_BAR_DARK else INSTAGRAM_LIGHT
        TargetApp.YOUTUBE ->
            if (surface == Surface.REELS || isNight()) YOUTUBE_DARK else YOUTUBE_LIGHT
        else -> colorFor(app, surface)
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
            TargetApp.YOUTUBE -> if (night) YOUTUBE_DARK else YOUTUBE_LIGHT
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

    /**
     * A report button, tucked into the top-right corner of the region it belongs to.
     * Touchable, like a blocker and unlike the cover: the whole point is to be tappable.
     */
    @SuppressLint("WrongConstant")
    private fun reportParams(region: Bounds) = WindowManager.LayoutParams().apply {
        val size = dp(REPORT_SIZE)
        val inset = dp(REPORT_INSET)
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        gravity = Gravity.TOP or Gravity.START
        x = (region.right - size - inset).coerceAtLeast(region.left)
        y = (region.top + inset).coerceAtMost(region.bottom - size)
        width = size
        height = size
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
        private var details: List<FeedItem> = emptyList()

        private val fill = Paint().apply { isAntiAlias = false }
        private val outline = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
        }
        private val caption = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = dp(13f)
        }

        fun setBands(next: List<Bounds>, nextDetails: List<FeedItem>) {
            if (next == bands && nextDetails == details) return
            bands = next
            details = nextDetails
            invalidate()
        }

        fun setFill(color: Int) {
            if (fill.color == color) return
            fill.color = color
            // Both marks have to read against whatever the cover is painted with.
            val light = isLight(color)
            outline.color = if (light) 0xFFDDDDDD.toInt() else 0xFF2E2E2E.toInt()
            caption.color = if (light) 0xFF9A9A9A.toInt() else 0xFF6E6E6E.toInt()
            invalidate()
        }

        private fun isLight(color: Int): Boolean {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            return (r * 299 + g * 587 + b * 114) / 1000 > 128
        }

        private fun dp(value: Float) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics,
        )

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

            // Outlines go on once the screen has settled. During a scroll the bounds are a
            // frame or two out of date, and an outline that lags is worse than none — the
            // flat cover reads as "still working" and nobody notices a missing border.
            for (item in details) {
                val top = maxOf(item.bounds.top, topInset).toFloat()
                val bottom = minOf(item.bounds.bottom.toFloat(), floor)
                if (bottom - top < dp(48f)) continue

                val inset = dp(6f)
                val rect = RectF(
                    item.bounds.left + inset,
                    top + inset,
                    item.bounds.right - inset,
                    bottom - inset,
                )
                val radius = dp(10f)
                canvas.drawRoundRect(rect, radius, radius, outline)
                canvas.drawText(
                    context.getString(labelFor(item.reason)),
                    rect.centerX(),
                    rect.centerY() + caption.textSize / 3f,
                    caption,
                )
            }
        }

        private fun labelFor(reason: Reason) = when (reason) {
            Reason.SUGGESTED -> R.string.hidden_suggested
            Reason.PROMOTED -> R.string.hidden_promoted
            Reason.FEED_MODULE -> R.string.hidden_module
            Reason.NETWORK_ACTIVITY -> R.string.hidden_activity
            Reason.REELS -> R.string.hidden_reels
            Reason.EXPLORE -> R.string.hidden_explore
            Reason.SHORTS_SHELF -> R.string.hidden_shorts
            else -> R.string.hidden_generic
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

    /**
     * The button on a cover that says "this one is wrong".
     *
     * Deliberately quiet: a translucent chip with a pointing finger on it. It has to be
     * findable when you want it and ignorable the rest of the time, because it sits on
     * every covered post.
     */
    @SuppressLint("ViewConstructor")
    private class ReportView(context: Context) : View(context) {

        var region: Bounds = Bounds.EMPTY
        private var onClick: ((Bounds) -> Unit)? = null
        private var pressed = false

        private val chip = Paint().apply {
            isAntiAlias = true
            color = 0x66000000
        }
        private val glyph = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            color = 0xFFEDEDED.toInt()
        }

        fun setOnClick(listener: (Bounds) -> Unit) {
            onClick = listener
        }

        override fun onDraw(canvas: Canvas) {
            val size = width.toFloat()
            glyph.textSize = size * 0.5f
            chip.color = if (pressed) 0xAA2E7D32.toInt() else 0x66000000
            val radius = size * 0.28f
            canvas.drawRoundRect(RectF(0f, 0f, size, height.toFloat()), radius, radius, chip)
            canvas.drawText(
                GLYPH,
                size / 2f,
                height / 2f + glyph.textSize / 3f,
                glyph,
            )
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = true
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    pressed = false
                    invalidate()
                    onClick?.invoke(region)
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressed = false
                    invalidate()
                }
            }
            return true
        }

        private companion object {
            const val GLYPH = "\uD83D\uDC47"
        }
    }
}
