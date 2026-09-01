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
