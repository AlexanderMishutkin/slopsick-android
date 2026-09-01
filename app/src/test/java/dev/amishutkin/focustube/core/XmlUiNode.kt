package dev.amishutkin.focustube.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A [UiNode] backed by a `uiautomator dump` XML file.
 *
 * This is what makes the analyzers testable without a device: the same trees the
 * accessibility service sees at runtime are captured once, anonymised (see
 * `tools/anonymize.py`) and replayed here.
 */
class XmlUiNode(private val element: Element) : UiNode {

    override val viewId: String? = element.getAttribute("resource-id").ifEmpty { null }
    override val className: String? = element.getAttribute("class").ifEmpty { null }
    override val text: String? = element.getAttribute("text").ifEmpty { null }
    override val contentDesc: String? = element.getAttribute("content-desc").ifEmpty { null }
    override val bounds: Bounds = parseBounds(element.getAttribute("bounds"))
    override val selected: Boolean = element.getAttribute("selected") == "true"

    override val children: List<UiNode> by lazy {
        val kids = mutableListOf<UiNode>()
        val nodes = element.childNodes
        for (i in 0 until nodes.length) {
            val child = nodes.item(i)
            if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == "node") {
                kids += XmlUiNode(child as Element)
            }
        }
        kids
    }

    companion object {
        private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

        private fun parseBounds(raw: String): Bounds {
            val m = BOUNDS.find(raw) ?: return Bounds.EMPTY
            val (l, t, r, b) = m.destructured
            return Bounds(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        }

        /** Root of the given fixture, as a synthetic node wrapping the hierarchy element. */
        fun load(file: File): UiNode {
            val factory = DocumentBuilderFactory.newInstance().apply {
                // Fixtures are local files committed to this repo, but there is no reason
                // for an XML parser in a test to ever reach the network.
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val doc = factory.newDocumentBuilder().parse(file)
            return XmlUiNode(doc.documentElement)
        }

        fun fixtures(): List<File> =
            File("src/test/resources/fixtures")
                .listFiles { f -> f.extension == "xml" }
                ?.sortedBy { it.name }
                ?: error("no fixtures found; run tools/anonymize.py first")

        fun fixture(name: String): UiNode =
            load(fixtures().firstOrNull { it.name == name } ?: error("no fixture named $name"))
    }
}
