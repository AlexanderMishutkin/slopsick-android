package dev.amishutkin.focustube.platform

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Returns a node to the system pool.
 *
 * Obsolete and a no-op from API 33, but before that these are pooled objects, and this
 * app walks a whole window several times a second while a feed is scrolling.
 */
@Suppress("DEPRECATION")
internal fun AccessibilityNodeInfo.recycleCompat() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        runCatching { recycle() }
    }
}
