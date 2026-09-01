package dev.amishutkin.focustube.core

/** Screen rectangle, in pixels, matching Android's left/top/right/bottom convention. */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun union(other: Bounds) = Bounds(
        minOf(left, other.left),
        minOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom),
    )

    fun intersects(other: Bounds) =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /** Height of the vertical overlap with [other]; 0 when they do not overlap. */
    fun verticalOverlap(other: Bounds) =
        (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0)

    companion object {
        val EMPTY = Bounds(0, 0, 0, 0)
    }
}

/**
 * One node of a UI tree.
 *
 * The analyzers work against this interface rather than against
 * `AccessibilityNodeInfo` so that the whole classification layer can be exercised
 * on the JVM, against XML dumps captured from real devices. That is the same trick
 * the browser extension uses with jsdom, and it is the only reason any of this is
 * testable without a phone in the loop.
 */
interface UiNode {
    /** Fully qualified view id, e.g. `com.instagram.android:id/row_feed_profile_header`. */
    val viewId: String?
    val className: String?
    val text: String?
    val contentDesc: String?
    val bounds: Bounds
    val children: List<UiNode>

    /** Whether the node is in a selected state — which tab of a tab bar is current. */
    val selected: Boolean
}

/** Depth-first sequence over this node and everything under it. */
fun UiNode.walk(): Sequence<UiNode> = sequence {
    yield(this@walk)
    for (child in children) yieldAll(child.walk())
}

/** True when the node's view id is exactly `<package>:id/<name>`. */
fun UiNode.hasId(name: String): Boolean {
    val id = viewId ?: return false
    val slash = id.lastIndexOf('/')
    return slash >= 0 && id.regionMatches(slash + 1, name, 0, name.length) &&
        id.length == slash + 1 + name.length
}

fun UiNode.findById(name: String): UiNode? = walk().firstOrNull { it.hasId(name) }

fun UiNode.containsId(name: String): Boolean = findById(name) != null

/** Every non-blank `text` and `contentDesc` under this node, in tree order. */
fun UiNode.labels(): List<String> =
    walk().flatMap { sequenceOf(it.text, it.contentDesc) }
        .filterNotNull()
        .map { it.replace('\u00a0', ' ').trim() }
        .filter { it.isNotEmpty() }
        .toList()
