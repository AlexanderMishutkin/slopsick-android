package dev.amishutkin.slopsick.platform

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import dev.amishutkin.slopsick.core.Bounds
import dev.amishutkin.slopsick.core.FeedScan
import dev.amishutkin.slopsick.core.Settings
import dev.amishutkin.slopsick.core.TargetApp
import dev.amishutkin.slopsick.core.UiNode
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Writes down what was on screen when you said it was wrong.
 *
 * Everything this tool gets wrong, it gets wrong about a screen — a shelf it did not
 * recognise, a post it covered that you wanted, a bar it painted over. None of that can
 * be worked out from a description, and none of it can be reproduced on another device:
 * the app version, the locale, the screen size and the account all change what the tree
 * looks like. What is needed is the tree itself.
 *
 * So the cover carries a button, and tapping it writes three files:
 *
 *  - `tree.xml`, in `uiautomator dump` format, so it drops straight into
 *    `app/src/test/resources/fixtures` and becomes a test — the whole corpus this thing
 *    is built against was made this way.
 *  - `report.json`, what the analyzer made of that tree: every item, its verdict, the
 *    reason, and the rectangles that were painted.
 *  - `screen.png`, taken with the covers **up**. Most of what goes wrong here is where a
 *    rectangle landed — a border in the wrong place, a bar covered, a strip left showing —
 *    and that is a picture of the covers, not of the feed. It also means the folder does
 *    not fill up with screenshots of the thing this app exists to stop you looking at.
 *
 * ## What this costs, said plainly
 *
 * `tree.xml` still contains other people's posts — names, handles, text — because that is
 * what the analyzer reads and a report that leaves it out cannot reproduce anything. The
 * screenshot does not. All of it is written to this app's own directory on the device and
 * goes nowhere else: the app holds no INTERNET permission, so it cannot send anything
 * anywhere, whatever the rest of this code says. Collecting them means plugging the phone
 * in:
 *
 *     adb pull /sdcard/Android/data/dev.amishutkin.slopsick/files/reports
 *
 * Run anything you intend to commit through `tools/anonymize.py` first. Nothing is ever
 * written unless the button is tapped, and the button only exists while the reporting
 * switch is on.
 *
 * The screenshot is the one thing here that needed a new capability
 * (`android:canTakeScreenshot` in the service config). It is worth knowing about; it is
 * declared in one place and used in one place, which is this file.
 */
class BugReporter(private val context: Context) {

    private val directRunner = Executor { it.run() }

    /** Where reports are written. Visible over adb, and to no other app. */
    fun directory(): File = File(context.getExternalFilesDir(null), REPORTS)

    /**
     * Writes a report for the region that was tapped.
     *
     * @param onFinished called on an arbitrary thread once every file is written
     */
    fun capture(
        service: AccessibilityService,
        app: TargetApp,
        region: Bounds,
        tree: UiNode?,
        scan: FeedScan?,
        settings: Settings,
        onFinished: (File?) -> Unit = {},
    ) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(directory(), "$stamp-${app.name.lowercase(Locale.US)}")
        if (!dir.mkdirs() && !dir.isDirectory) {
            Log.w(TAG, "could not create $dir")
            return onFinished(null)
        }

        runCatching { File(dir, "report.json").writeText(json(app, region, scan, settings)) }
            .onFailure { Log.w(TAG, "report.json", it) }
        if (tree != null) {
            runCatching { File(dir, "tree.xml").writeText(xml(tree, app)) }
                .onFailure { Log.w(TAG, "tree.xml", it) }
        }
        screenshot(service, File(dir, "screen.png")) { onFinished(dir) }
    }

    // --- the screenshot --------------------------------------------------------------

    /** The display as it is, covers included — see the note on `screen.png` above. */
    private fun screenshot(service: AccessibilityService, file: File, done: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return done()
        runCatching {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                directRunner,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        runCatching {
                            val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            // A hardware bitmap cannot be compressed directly.
                            val copy = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            bitmap?.recycle()
                            copy?.let {
                                FileOutputStream(file).use { out ->
                                    it.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                                }
                                it.recycle()
                            }
                        }.onFailure { Log.w(TAG, "screen.png", it) }
                        buffer.close()
                        done()
                    }

                    override fun onFailure(errorCode: Int) {
                        // The system rate-limits this to about one a second, and refuses
                        // outright on some secure windows. A report without a picture is
                        // still a report.
                        Log.w(TAG, "screenshot refused, code $errorCode")
                        done()
                    }
                },
            )
        }.onFailure {
            Log.w(TAG, "screenshot", it)
            done()
        }
    }

    // --- the files -------------------------------------------------------------------

    /**
     * The tree in `uiautomator dump` format, which is the format the test fixtures are
     * in. Only the attributes [dev.amishutkin.slopsick.core.UiNode] carries are written:
     * a fixture is read back through the same interface the analyzers see, so anything
     * else would be decoration.
     */
    private fun xml(root: UiNode, app: TargetApp): String {
        val out = StringBuilder(64 * 1024)
        out.append("<?xml version='1.0' encoding='utf-8'?>\n<hierarchy rotation=\"0\">")
        write(root, app, out)
        out.append("</hierarchy>\n")
        return out.toString()
    }

    private fun write(node: UiNode, app: TargetApp, out: StringBuilder) {
        val b = node.bounds
        out.append("<node")
            .append(" text=\"").append(escape(node.text)).append('"')
            .append(" resource-id=\"").append(escape(node.viewId)).append('"')
            .append(" class=\"").append(escape(node.className)).append('"')
            .append(" package=\"").append(app.packageName).append('"')
            .append(" content-desc=\"").append(escape(node.contentDesc)).append('"')
            .append(" selected=\"").append(node.selected).append('"')
            .append(" bounds=\"[").append(b.left).append(',').append(b.top)
            .append("][").append(b.right).append(',').append(b.bottom).append("]\"")
        if (node.children.isEmpty()) {
            out.append(" />")
            return
        }
        out.append('>')
        for (child in node.children) write(child, app, out)
        out.append("</node>")
    }

    private fun json(
        app: TargetApp,
        region: Bounds,
        scan: FeedScan?,
        settings: Settings,
    ): String {
        val out = StringBuilder()
        out.append("{\n")
        out.append("  \"app\": \"").append(app.name).append("\",\n")
        out.append("  \"package\": \"").append(app.packageName).append("\",\n")
        out.append("  \"reportedAt\": \"")
            .append(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
            .append("\",\n")
        out.append("  \"reportedRegion\": ").append(rect(region)).append(",\n")
        out.append("  \"device\": \"").append(escape(Build.MANUFACTURER + " " + Build.MODEL))
            .append("\",\n")
        out.append("  \"android\": ").append(Build.VERSION.SDK_INT).append(",\n")
        out.append("  \"appVersion\": \"").append(escape(versionOf(app.packageName))).append("\",\n")
        out.append("  \"settings\": {")
            .append("\"instagram\": ").append(settings.instagram)
            .append(", \"linkedIn\": ").append(settings.linkedIn)
            .append(", \"youtube\": ").append(settings.youtube)
            .append("},\n")
        out.append("  \"surface\": \"").append(scan?.surface?.name ?: "NONE").append("\",\n")
        out.append("  \"feed\": ").append(scan?.feedBounds?.let { rect(it) } ?: "null").append(",\n")
        out.append("  \"safe\": ").append(scan?.safe?.let { rect(it) } ?: "null").append(",\n")
        out.append("  \"blackouts\": ")
            .append((scan?.blackouts ?: emptyList()).joinToString(", ", "[", "]") { rect(it) })
            .append(",\n")
        out.append("  \"blockers\": ")
            .append((scan?.blockers ?: emptyList()).joinToString(", ", "[", "]") { rect(it) })
            .append(",\n")
        out.append("  \"items\": [\n")
        val items = scan?.items ?: emptyList()
        for ((i, item) in items.withIndex()) {
            out.append("    {\"verdict\": \"").append(item.verdict.name)
                .append("\", \"reason\": \"").append(item.reason.name)
                .append("\", \"bounds\": ").append(rect(item.bounds))
                .append("}")
            if (i < items.size - 1) out.append(',')
            out.append('\n')
        }
        out.append("  ]\n}\n")
        return out.toString()
    }

    private fun rect(b: Bounds) =
        "[${b.left}, ${b.top}, ${b.right}, ${b.bottom}]"

    private fun versionOf(packageName: String): String = runCatching {
        context.packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun escape(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val out = StringBuilder(value.length + 16)
        for (c in value) {
            when {
                c == '&' -> out.append("&amp;")
                c == '<' -> out.append("&lt;")
                c == '>' -> out.append("&gt;")
                c == '"' -> out.append("&quot;")
                c == '\n' -> out.append("&#10;")
                // Control characters are not legal in XML and no app means to emit them.
                c.code < 0x20 -> out.append(' ')
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private companion object {
        const val TAG = "Slopsick"
        const val REPORTS = "reports"
        const val PNG_QUALITY = 100
    }
}
