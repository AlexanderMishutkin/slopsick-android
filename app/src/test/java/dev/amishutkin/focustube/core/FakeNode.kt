package dev.amishutkin.focustube.core

/** A hand-built tree, for rules the captured fixtures cannot express. */
data class FakeNode(
    override val viewId: String? = null,
    override val className: String? = null,
    override val text: String? = null,
    override val contentDesc: String? = null,
    override val bounds: Bounds = Bounds(0, 0, 1000, 1000),
    override val children: List<UiNode> = emptyList(),
) : UiNode

/** A LinkedIn-shaped screen: a lazy column holding one post with the given labels. */
fun linkedInScreen(vararg labels: String, height: Int = 1000): UiNode =
    FakeNode(
        bounds = Bounds(0, 0, 1000, height),
        children = listOf(
            FakeNode(
                viewId = "sdui:lazyColumn",
                bounds = Bounds(0, 0, 1000, height),
                children = listOf(
                    FakeNode(
                        bounds = Bounds(0, 0, 1000, height),
                        children = labels.map { FakeNode(text = it, bounds = Bounds(0, 0, 1000, 10)) },
                    ),
                ),
            ),
        ),
    )

/**
 * An Instagram-shaped screen showing the Reels player.
 *
 * Built by hand rather than captured: `uiautomator dump` cannot read Reels, because it
 * waits for a window that stops changing and an autoplaying video never does. The ids
 * and bounds are copied from a tree logged off a real device by the service itself.
 */
fun instagramReelsScreen(): UiNode = FakeNode(
    bounds = Bounds(0, 0, 1080, 2400),
    children = listOf(
        FakeNode(
            viewId = "com.instagram.android:id/clips_viewer_container",
            bounds = Bounds(0, 63, 1080, 2211),
            children = listOf(
                FakeNode(
                    viewId = "com.instagram.android:id/clips_viewer_view_pager",
                    bounds = Bounds(0, 63, 1080, 2211),
                ),
            ),
        ),
        instagramTabBar(),
    ),
)

/** Instagram's bottom navigation, with the Reels tab where the device puts it. */
fun instagramTabBar(): UiNode = FakeNode(
    viewId = "com.instagram.android:id/tab_bar",
    bounds = Bounds(0, 2211, 1080, 2337),
    children = listOf(
        FakeNode(
            viewId = "com.instagram.android:id/feed_tab",
            bounds = Bounds(0, 2211, 216, 2337),
        ),
        FakeNode(
            viewId = "com.instagram.android:id/clips_tab",
            bounds = Bounds(216, 2211, 432, 2337),
        ),
    ),
)

/** An Instagram screen that is neither feed nor Reels — a profile, say. */
fun instagramOtherScreen(): UiNode = FakeNode(
    bounds = Bounds(0, 0, 1080, 2400),
    children = listOf(instagramTabBar()),
)
