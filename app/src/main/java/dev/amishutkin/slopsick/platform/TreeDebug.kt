package dev.amishutkin.slopsick.platform

import android.util.Log
import dev.amishutkin.slopsick.BuildConfig
import dev.amishutkin.slopsick.core.UiNode

/**
 * Prints the view ids on screen, for working out how to recognise a surface.
 *
 * `uiautomator dump` cannot read a screen that never stops animating — Reels, or any
 * autoplaying video — because it waits for an idle window that never comes. This service
 * is already holding the tree, so it can print what uiautomator cannot reach.
 *
 * **View ids and geometry only, never text or content descriptions.** Logcat is readable
 * by anything holding READ_LOGS, and this app reads other people's posts. Debug builds
 * only, and off unless switched on:
 *
 *     adb shell setprop log.tag.SlopsickTree VERBOSE
 */
internal object TreeDebug {

    private const val TAG = "SlopsickTree"

    fun dump(root: UiNode) {
        if (!BuildConfig.DEBUG || !Log.isLoggable(TAG, Log.VERBOSE)) return
        val out = StringBuilder()
        walk(root, 0, out)
        // Logcat truncates a single entry, so emit it in slices.
        out.toString().chunked(3000).forEach { Log.v(TAG, it) }
    }

    private fun walk(node: UiNode, depth: Int, out: StringBuilder) {
        val id = node.viewId
        if (id != null) {
            out.append("  ".repeat(depth.coerceAtMost(12)))
                .append(id.substringAfterLast('/'))
                .append(' ')
                .append(node.bounds.top).append("..").append(node.bounds.bottom)
                .append('\n')
        }
        for (child in node.children) walk(child, if (id != null) depth + 1 else depth, out)
    }
}
