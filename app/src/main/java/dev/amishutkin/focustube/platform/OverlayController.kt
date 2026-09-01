package dev.amishutkin.focustube.platform

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import dev.amishutkin.focustube.core.Bounds

/**
 * Draws the covering bands.
 *
 * An accessibility service cannot hide another app's views — it can only draw on top of
 * them — so a covered post is still there, still scrolls, and can still be tapped. The
 * window is created with FLAG_NOT_TOUCHABLE so that every gesture reaches the app
 * underneath: scrolling and flinging must feel exactly as they did, and reimplementing
 * Instagram's scroll physics is not on the table.
 */
class OverlayController(private val context: Context) {

    private companion object {
        const val TAG = "FocusTube"

        /**
         * Geometry only — never text taken from the feed. A log line is readable by any
         * app holding READ_LOGS, and this one reads other people's posts for a living.
         */
        val DEBUG = dev.amishutkin.focustube.BuildConfig.DEBUG
    }

    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: CoverView? = null

    fun show(bands: List<Bounds>) {
        if (bands.isEmpty()) {
            hide()
            return
        }
        val target = view ?: CoverView(context).also {
            val added = runCatching { windows.addView(it, layoutParams()) }
            if (added.isFailure) {
                Log.w(TAG, "could not add overlay window", added.exceptionOrNull())
                return
            }
            view = it
        }
        target.setBands(bands)
    }

    fun hide() {
        view?.let { runCatching { windows.removeView(it) } }
        view = null
    }

    @SuppressLint("WrongConstant")
    private fun layoutParams() = WindowManager.LayoutParams().apply {
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

    private class CoverView(context: Context) : View(context) {

        private var bands: List<Bounds> = emptyList()

        private val fill = Paint().apply {
            color = Color.parseColor("#F2111517")
            isAntiAlias = false
        }

        fun setBands(next: List<Bounds>) {
            if (next == bands) return
            bands = next
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
}
