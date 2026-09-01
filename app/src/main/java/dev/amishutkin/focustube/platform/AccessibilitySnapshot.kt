package dev.amishutkin.focustube.platform

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import dev.amishutkin.focustube.core.Bounds
import dev.amishutkin.focustube.core.UiNode

/**
 * An immutable copy of a live accessibility tree.
 *
 * The analyzers must not touch `AccessibilityNodeInfo` directly. Those objects are
 * owned by the system, are only valid while the window they came from is unchanged,
 * and reading one can block. Copying the handful of fields that matter into plain data
 * up front means the analysis is a pure function over a value — which is what makes it
 * the same code the JVM tests exercise, rather than a parallel implementation of it.
 */
class SnapshotNode(
    override val viewId: String?,
    override val className: String?,
    override val text: String?,
    override val contentDesc: String?,
    override val bounds: Bounds,
    override val children: List<UiNode>,
) : UiNode {

    companion object {
        /**
         * Trees this deep or wide are not feeds, and walking them would cost more than
         * the frame budget allows. Instagram's feed runs to roughly 30 levels and a few
         * hundred nodes; these leave generous headroom.
         */
        private const val MAX_DEPTH = 60
        private const val MAX_NODES = 4000

        fun of(root: AccessibilityNodeInfo?): UiNode? {
            if (root == null) return null
            val budget = intArrayOf(MAX_NODES)
            return copy(root, 0, budget)
        }

        private fun copy(node: AccessibilityNodeInfo, depth: Int, budget: IntArray): UiNode? {
            if (depth > MAX_DEPTH || budget[0] <= 0) return null
            budget[0]--

            val rect = Rect().also { node.getBoundsInScreen(it) }
            val children = ArrayList<UiNode>(node.childCount)
            for (i in 0 until node.childCount) {
                val child = try {
                    node.getChild(i)
                } catch (_: Exception) {
                    // The window can change under us mid-walk; a partial tree is fine,
                    // because anything it fails to explain stays covered.
                    null
                } ?: continue
                copy(child, depth + 1, budget)?.let { children += it }
            }

            return SnapshotNode(
                viewId = node.viewIdResourceName,
                className = node.className?.toString(),
                text = node.text?.toString(),
                contentDesc = node.contentDescription?.toString(),
                bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
                children = children,
            )
        }
    }
}
