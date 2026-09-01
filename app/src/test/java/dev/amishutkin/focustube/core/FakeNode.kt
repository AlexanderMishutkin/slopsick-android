package dev.amishutkin.focustube.core

/** A hand-built tree, for rules the captured fixtures cannot express. */
data class FakeNode(
    override val viewId: String? = null,
    override val className: String? = null,
    override val text: String? = null,
    override val contentDesc: String? = null,
    override val bounds: Bounds = Bounds(0, 0, 1000, 1000),
    override val children: List<UiNode> = emptyList(),
    override val selected: Boolean = false,
) : UiNode

/**
 * A LinkedIn-shaped screen: a lazy column holding one post with the given labels.
 *
 * The selected feed tab is part of the shape, not decoration — every LinkedIn tab is a
 * lazy column, and without it this would be indistinguishable from Jobs.
 */
fun linkedInScreen(vararg labels: String, height: Int = 1000, onFeed: Boolean = true): UiNode =
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
            linkedInBottomBar(selectedTab = if (onFeed) "tab_feed" else "tab_jobs"),
        ),
    )

/** LinkedIn's bottom navigation, with one tab marked current. */
fun linkedInBottomBar(selectedTab: String): UiNode = FakeNode(
    viewId = "com.linkedin.android:id/home_bottom_bar",
    bounds = Bounds(0, 2211, 1080, 2337),
    children = listOf("tab_feed", "tab_relationships", "tab_post", "tab_notifications", "tab_jobs")
        .mapIndexed { index, tab ->
            FakeNode(
                viewId = "com.linkedin.android:id/" + tab,
                bounds = Bounds(index * 216, 2211, (index + 1) * 216, 2337),
                selected = tab == selectedTab,
            )
        },
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

/** Instagram's Explore tab: a search bar, a grid of suggestions, the tab bar. */
fun instagramExploreScreen(): UiNode = FakeNode(
    bounds = Bounds(0, 0, 1080, 2400),
    children = listOf(
        FakeNode(
            viewId = "com.instagram.android:id/recycler_view",
            className = "androidx.recyclerview.widget.RecyclerView",
            bounds = Bounds(0, 63, 1080, 2211),
        ),
        FakeNode(
            viewId = "com.instagram.android:id/explore_action_bar",
            bounds = Bounds(0, 63, 1080, 210),
            children = listOf(
                FakeNode(
                    viewId = "com.instagram.android:id/action_bar_search_edit_text",
                    text = "Search",
                    bounds = Bounds(32, 63, 939, 155),
                ),
            ),
        ),
        instagramTabBar(),
    ),
)

/**
 * What Explore turns into once you type something. Instagram drops the explore action
 * bar here, which is the only thing separating "results you asked for" from "a grid of
 * things the algorithm picked".
 */
fun instagramSearchResultsScreen(): UiNode = FakeNode(
    bounds = Bounds(0, 0, 1080, 2400),
    children = listOf(
        FakeNode(
            viewId = "com.instagram.android:id/recycler_view",
            className = "androidx.recyclerview.widget.RecyclerView",
            bounds = Bounds(0, 210, 1080, 2337),
            children = listOf(
                FakeNode(
                    viewId = "com.instagram.android:id/row_search_keyword_title",
                    text = "nasa",
                    bounds = Bounds(0, 242, 1080, 330),
                ),
            ),
        ),
    ),
)

// --- YouTube -----------------------------------------------------------------------

fun youtubeNavBar(): UiNode = FakeNode(
    viewId = "com.google.android.youtube:id/pivot_bar",
    bounds = Bounds(0, 2211, 1080, 2337),
    children = listOf(
        FakeNode(
            bounds = Bounds(0, 2211, 1080, 2337),
            children = listOf(
                FakeNode(
                    contentDesc = "Home",
                    bounds = Bounds(0, 2211, 216, 2337),
                    children = listOf(
                        FakeNode(
                            viewId = "com.google.android.youtube:id/text",
                            text = "Home",
                            bounds = Bounds(70, 2293, 147, 2323),
                        ),
                    ),
                ),
                FakeNode(
                    contentDesc = "Shorts",
                    bounds = Bounds(216, 2211, 432, 2337),
                    children = listOf(
                        FakeNode(
                            viewId = "com.google.android.youtube:id/text",
                            text = "Shorts",
                            bounds = Bounds(283, 2293, 365, 2323),
                        ),
                    ),
                ),
            ),
        ),
    ),
)

fun youtubeShortsPlayer(): UiNode = FakeNode(
    bounds = Bounds(0, 0, 1080, 2400),
    children = listOf(
        FakeNode(
            viewId = "com.google.android.youtube:id/reel_watch_fragment_root",
            bounds = Bounds(0, 63, 1080, 2211),
        ),
        youtubeNavBar(),
    ),
)

/**
 * A Shorts shelf as YouTube actually builds it: the heading is one child of the feed and
 * the videos are the next, each item describing itself as "... - play Short".
 */
fun youtubeShortsShelfRows(): List<List<String>> = listOf(
    listOf("Shorts", "Action menu"),
    listOf(
        "Iron man engineering in real life, Engineering realities, 3 weeks ago - play Short",
        "I made a Circle Plane, ProjectAir, 4 days ago - play Short",
    ),
)

/** YouTube's home feed. Each row's labels are given as one list per row. */
fun youtubeHome(vararg rows: List<String>): UiNode = FakeNode(
    bounds = Bounds(0, 0, 1080, 2400),
    children = listOf(
        FakeNode(
            viewId = "com.google.android.youtube:id/results",
            className = "androidx.recyclerview.widget.RecyclerView",
            bounds = Bounds(0, 315, 1080, 2211),
            children = rows.mapIndexed { index, labels ->
                val top = 315 + index * 600
                FakeNode(
                    bounds = Bounds(0, top, 1080, top + 600),
                    children = labels.map {
                        FakeNode(text = it, bounds = Bounds(0, top, 1080, top + 40))
                    },
                )
            },
        ),
        youtubeNavBar(),
    ),
)
