package me.rerere.rikkahub.phoneuse

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.util.Xml
import android.view.WindowManager
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.hostshell.HostShellExecutor
import me.rerere.rikkahub.hostshell.shellSingleQuote
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.StringReader
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val MAX_ELEMENTS = 150
private const val MAX_LABEL_CHARS = 80
private const val MAX_LABEL_PARTS = 3
private const val JPEG_QUALITY = 80

/**
 * 基于 Sui / Shizuku / Root (UID 2000) 的免无障碍 [PhoneController] 实现。
 *
 * 完全无需开启系统无障碍服务（彻底规避金融/游戏风控与无障碍掉帧）：
 * - 屏幕结构读取：`uiautomator dump /proc/self/fd/1` 内存直出 XML 解析
 * - 屏幕截图：`screencap -p` 二进制流缩放压缩
 * - 点击与手势：优先走 Sui/Shizuku Binder 直连 `IInputManager`，回退 `input tap` / `input swipe` + [GestureIndicator]
 * - 中西文输入：主线程暂存并写入剪贴板 + `KEYCODE_PASTE` (279)，粘贴后自动还原原剪贴板内容
 * - 后台启动应用：`am start` 直接绕过 Android 10+ 后台拉起限制
 */
class RootPhoneController(
    private val context: Context,
    private val executor: HostShellExecutor,
) : PhoneController {

    private val indicator by lazy { GestureIndicator(context) }

    @Volatile
    private var lastElements: List<PhoneElement> = emptyList()

    @Volatile
    private var cachedVisiblePackages: Set<String> = emptySet()

    override suspend fun visiblePackages(): Set<String> = withContext(Dispatchers.IO) {
        queryVisiblePackages()
    }

    private suspend fun queryVisiblePackages(): Set<String> {
        val res = executor.execute(
            command = "dumpsys window windows",
            asRoot = false,
            timeoutMs = 5_000L,
        )
        if (res.exitCode != 0 || res.stdout.isBlank()) {
            return cachedVisiblePackages
        }
        return parseVisiblePackagesFromWindowDump(res.stdout, context.packageName).also {
            if (it.isNotEmpty()) cachedVisiblePackages = it
        }
    }

    override suspend fun captureScreen(): PhoneScreen = withContext(Dispatchers.IO) {
        val size = screenSize()
        val screenBounds = Rect(0, 0, size.x, size.y)

        // 先尝试内存零落盘导出 XML；个别魔改 ROM 不支持 /proc/self/fd/1 时回退 /data/local/tmp
        var dumpRes = executor.execute(
            command = "uiautomator dump /proc/self/fd/1",
            asRoot = false,
            timeoutMs = 10_000L,
        )
        var xmlText = extractXmlHierarchy(dumpRes.stdout)
        if (xmlText == null) {
            dumpRes = executor.execute(
                command = "uiautomator dump /data/local/tmp/rikkahub_uidump.xml >/dev/null && cat /data/local/tmp/rikkahub_uidump.xml",
                asRoot = false,
                timeoutMs = 12_000L,
            )
            xmlText = extractXmlHierarchy(dumpRes.stdout)
        }

        val winRes = executor.execute(
            command = "dumpsys window windows; echo '===IME==='; dumpsys input_method | grep -E 'mInputShown='",
            asRoot = false,
            timeoutMs = 6_000L,
        )
        val winParts = winRes.stdout.split("===IME===")
        val winDump = winParts.getOrNull(0).orEmpty()
        val imeDump = winParts.getOrNull(1).orEmpty()

        val visiblePkgs = parseVisiblePackagesFromWindowDump(winDump, context.packageName)
        val focusedPkg = parseFocusedPackage(winDump, context.packageName)
        val keyboardVisible = imeDump.contains("mInputShown=true")

        val parsed = if (xmlText != null) {
            parseUiAutomatorXml(xmlText, screenBounds, context.packageName)
        } else {
            ParsedUiTree(emptyList(), emptySet(), false)
        }

        lastElements = parsed.elements
        val allPackages = (visiblePkgs + parsed.packages).filterTo(LinkedHashSet()) {
            it.isNotBlank() && it != context.packageName && it != "com.android.systemui"
        }
        val topPkg = focusedPkg
            ?: parsed.packages.firstOrNull { it != "com.android.systemui" && it != context.packageName }
            ?: allPackages.firstOrNull()

        cachedVisiblePackages = allPackages

        PhoneScreen(
            packageName = topPkg,
            appLabel = topPkg?.let(::appLabel),
            packages = allPackages,
            width = size.x,
            height = size.y,
            keyboardVisible = keyboardVisible,
            elements = parsed.elements,
            truncated = parsed.truncated,
        )
    }

    override suspend fun screenshot(maxSide: Int): ByteArray = withContext(Dispatchers.IO) {
        val res = executor.execute(
            command = "screencap -p",
            asRoot = false,
            timeoutMs = 10_000L,
            maxStdoutBytes = 16 * 1024 * 1024,
        )
        check(res.exitCode == 0 && res.stdoutBytes.isNotEmpty()) {
            "screencap failed: ${res.stderr.ifBlank { "exit ${res.exitCode}" }}"
        }
        val bitmap = BitmapFactory.decodeByteArray(res.stdoutBytes, 0, res.stdoutBytes.size)
            ?: error("Failed to decode screencap PNG image")
        val ratio = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (ratio < 1f) {
            bitmap.scale((bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1))
        } else {
            bitmap
        }
        ByteArrayOutputStream().also {
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
        }.toByteArray()
    }

    override suspend fun gesture(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        val fx = fromX.coerceAtLeast(0f)
        val fy = fromY.coerceAtLeast(0f)
        val tx = toX.coerceAtLeast(0f)
        val ty = toY.coerceAtLeast(0f)

        withContext(Dispatchers.Main) {
            indicator.show(fx, fy, tx, ty, durationMs)
        }

        // 优先走 Sui / Shizuku Binder 直连 IInputManager（~1-2ms 响应），失败时自动回退命令行 input
        if (executor.canUseBinderFastInput && ShizukuFastInput.injectGesture(fx, fy, tx, ty, durationMs)) {
            return@withContext true
        }

        val isQuickTap = hypot(tx - fx, ty - fy) < 12f && durationMs <= 100L
        val cmd = if (isQuickTap) {
            "input tap ${fx.roundToInt()} ${fy.roundToInt()}"
        } else {
            "input swipe ${fx.roundToInt()} ${fy.roundToInt()} ${tx.roundToInt()} ${ty.roundToInt()} ${durationMs.coerceAtLeast(50L)}"
        }
        val res = executor.execute(cmd, asRoot = false, timeoutMs = durationMs + 5_000L)
        res.exitCode == 0
    }

    override suspend fun setText(elementIndex: Int?, text: String): Boolean = withContext(Dispatchers.IO) {
        val fastInput = executor.canUseBinderFastInput
        if (elementIndex != null) {
            val el = lastElements.getOrNull(elementIndex) ?: return@withContext false
            val cx = ((el.left + el.right) / 2f).roundToInt()
            val cy = ((el.top + el.bottom) / 2f).roundToInt()
            withContext(Dispatchers.Main) {
                indicator.show(cx.toFloat(), cy.toFloat(), cx.toFloat(), cy.toFloat(), 60L)
            }
            val tapped = (fastInput && ShizukuFastInput.injectTap(cx.toFloat(), cy.toFloat())) ||
                executor.execute("input tap $cx $cy", asRoot = false, timeoutMs = 4_000L).exitCode == 0
            if (!tapped) return@withContext false
            delay(200L)
        }

        // 全选并清除输入框原有内容（Ctrl+A -> DEL）
        if (fastInput && ShizukuFastInput.injectCtrlA()) {
            ShizukuFastInput.injectKeyCode(android.view.KeyEvent.KEYCODE_DEL)
        } else {
            executor.execute(
                command = "input keycombination 113 29 2>/dev/null; input keyevent 67",
                asRoot = false,
                timeoutMs = 4_000L,
            )
        }

        if (text.isEmpty()) return@withContext true

        // 在主线程暂存旧剪贴板、写入待输入文本并用 KEYCODE_PASTE (279) 粘贴，完成后恢复用户剪贴板
        var previousClip: ClipData? = null
        withContext(Dispatchers.Main) {
            val cm = context.getSystemService(ClipboardManager::class.java)
            previousClip = runCatching { cm?.primaryClip }.getOrNull()
            cm?.setPrimaryClip(ClipData.newPlainText("rikkahub_input", text))
        }
        try {
            delay(60L)
            val pasted = (fastInput && ShizukuFastInput.injectKeyCode(android.view.KeyEvent.KEYCODE_PASTE)) ||
                executor.execute("input keyevent 279", asRoot = false, timeoutMs = 4_000L).exitCode == 0
            if (pasted) {
                delay(80L)
            }
            return@withContext pasted
        } finally {
            previousClip?.let { saved ->
                withContext(Dispatchers.Main) {
                    runCatching {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(saved)
                    }
                }
            }
        }
    }

    override suspend fun pressKey(key: PhoneKey): Boolean = withContext(Dispatchers.IO) {
        if (executor.canUseBinderFastInput) {
            val fastCode = when (key) {
                PhoneKey.BACK -> android.view.KeyEvent.KEYCODE_BACK
                PhoneKey.HOME -> android.view.KeyEvent.KEYCODE_HOME
                PhoneKey.RECENTS -> android.view.KeyEvent.KEYCODE_APP_SWITCH
                PhoneKey.ENTER -> android.view.KeyEvent.KEYCODE_ENTER
                PhoneKey.NOTIFICATIONS -> null
            }
            if (fastCode != null && ShizukuFastInput.injectKeyCode(fastCode)) {
                return@withContext true
            }
        }
        val cmd = when (key) {
            PhoneKey.BACK -> "input keyevent 4"
            PhoneKey.HOME -> "input keyevent 3"
            PhoneKey.RECENTS -> "input keyevent 187"
            PhoneKey.NOTIFICATIONS -> "cmd statusbar expand-notifications || input keyevent 83"
            PhoneKey.ENTER -> "input keyevent 66"
        }
        executor.execute(cmd, asRoot = false, timeoutMs = 5_000L).exitCode == 0
    }

    override fun listApps(): List<PhoneApp> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { PhoneApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    override suspend fun openApp(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return@withContext false
        val component = launchIntent.component ?: return@withContext false
        val flat = "${component.packageName}/${component.className}"
        val res = executor.execute(
            command = "am start -n ${shellSingleQuote(flat)}",
            asRoot = false,
            timeoutMs = 6_000L,
        )
        res.exitCode == 0 && !res.stderr.contains("Error", ignoreCase = true)
    }

    private fun screenSize(): Point {
        val wm = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            return Point(bounds.width(), bounds.height())
        }
        @Suppress("DEPRECATION")
        return Point().also { wm.defaultDisplay.getRealSize(it) }
    }

    private fun appLabel(packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()
}

internal data class ParsedUiTree(
    val elements: List<PhoneElement>,
    val packages: Set<String>,
    val truncated: Boolean,
)

private class RawUiNode(
    val packageName: String,
    val className: String,
    val text: String,
    val contentDesc: String,
    val hint: String,
    val resourceId: String,
    val bounds: Rect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val focused: Boolean,
    val editable: Boolean,
    val children: MutableList<RawUiNode> = mutableListOf(),
) {
    fun ownLabel(): String =
        text.takeIf { it.isNotBlank() }
            ?: contentDesc.takeIf { it.isNotBlank() }
            ?: hint.takeIf { it.isNotBlank() }
            ?: ""

    fun descendantLabels(): List<String> {
        val labels = mutableListOf<String>()
        fun collect(n: RawUiNode) {
            for (child in n.children) {
                if (labels.size >= MAX_LABEL_PARTS) return
                if (child.clickable) continue
                child.ownLabel().takeIf { it.isNotEmpty() }?.let(labels::add)
                collect(child)
            }
        }
        collect(this)
        return labels
    }
}

private val BOUNDS_REGEX = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
private val WINDOW_HEADER_REGEX = Regex("""Window\{[0-9a-fA-F]+\s+u\d+\s+([a-zA-Z0-9_.]+)/""")
private val FOCUS_PKG_REGEX = Regex("""mCurrentFocus=Window\{[0-9a-fA-F]+\s+u\d+\s+([a-zA-Z0-9_.]+)/""")

internal fun extractXmlHierarchy(raw: String): String? {
    val start = raw.indexOf("<?xml").takeIf { it >= 0 } ?: raw.indexOf("<hierarchy")
    val end = raw.lastIndexOf("</hierarchy>")
    if (start < 0 || end < start) return null
    return raw.substring(start, end + "</hierarchy>".length)
}

internal fun parseUiAutomatorXml(
    xml: String,
    screenBounds: Rect,
    ownPackageName: String,
): ParsedUiTree {
    val parser = Xml.newPullParser()
    parser.setInput(StringReader(xml))

    val stack = ArrayDeque<RawUiNode>()
    val roots = mutableListOf<RawUiNode>()
    val packages = LinkedHashSet<String>()

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> {
                if (parser.name == "node") {
                    val pkg = parser.getAttributeValue(null, "package").orEmpty()
                    val cls = parser.getAttributeValue(null, "class").orEmpty()
                    val text = parser.getAttributeValue(null, "text").orEmpty()
                    val desc = parser.getAttributeValue(null, "content-desc").orEmpty()
                    val hint = parser.getAttributeValue(null, "hint").orEmpty()
                    val resId = parser.getAttributeValue(null, "resource-id").orEmpty()
                    val boundsStr = parser.getAttributeValue(null, "bounds").orEmpty()
                    val clickable = parser.getAttributeValue(null, "clickable") == "true"
                    val longClickable = parser.getAttributeValue(null, "long-clickable") == "true"
                    val scrollable = parser.getAttributeValue(null, "scrollable") == "true"
                    val checkable = parser.getAttributeValue(null, "checkable") == "true"
                    val checked = parser.getAttributeValue(null, "checked") == "true"
                    val focused = parser.getAttributeValue(null, "focused") == "true"
                    val editable = cls.contains("EditText", ignoreCase = true) ||
                        cls.contains("AutoCompleteTextView", ignoreCase = true)

                    if (pkg.isNotBlank() && pkg != ownPackageName && pkg != "com.android.systemui") {
                        packages += pkg
                    }

                    val node = RawUiNode(
                        packageName = pkg,
                        className = cls,
                        text = text,
                        contentDesc = desc,
                        hint = hint,
                        resourceId = resId,
                        bounds = parseBounds(boundsStr),
                        clickable = clickable,
                        longClickable = longClickable,
                        scrollable = scrollable,
                        checkable = checkable,
                        checked = checked,
                        focused = focused,
                        editable = editable,
                    )
                    if (stack.isEmpty()) {
                        roots += node
                    } else {
                        stack.last().children += node
                    }
                    stack.addLast(node)
                }
            }

            XmlPullParser.END_TAG -> {
                if (parser.name == "node" && stack.isNotEmpty()) {
                    stack.removeLast()
                }
            }
        }
        event = parser.next()
    }

    val elements = mutableListOf<PhoneElement>()
    var truncated = false

    fun visit(node: RawUiNode, absorbed: Boolean) {
        if (elements.size >= MAX_ELEMENTS) {
            truncated = true
            return
        }
        if (node.packageName == ownPackageName) return

        var childrenAbsorbed = absorbed
        val bounds = Rect(node.bounds)
        val hasPositiveArea = bounds.width() > 0 && bounds.height() > 0
        val interactive = node.clickable || node.longClickable || node.editable || node.scrollable || node.checkable

        var label = node.ownLabel()
        if (label.isEmpty() && node.clickable) {
            label = node.descendantLabels().joinToString(" ")
            childrenAbsorbed = label.isNotEmpty()
        }
        val listed = interactive || (label.isNotEmpty() && !absorbed)
        if (hasPositiveArea && listed && Rect.intersects(bounds, screenBounds)) {
            elements += PhoneElement(
                index = elements.size,
                className = node.className.substringAfterLast('.').ifBlank { "View" },
                label = label.cleanLabel(),
                resourceId = node.resourceId.substringAfterLast('/').takeIf { it.isNotBlank() },
                left = bounds.left.coerceAtLeast(0),
                top = bounds.top.coerceAtLeast(0),
                right = bounds.right.coerceAtMost(screenBounds.right),
                bottom = bounds.bottom.coerceAtMost(screenBounds.bottom),
                clickable = node.clickable,
                editable = node.editable,
                scrollable = node.scrollable,
                checked = if (node.checkable) node.checked else null,
                focused = node.editable && node.focused,
            )
        }
        for (child in node.children) {
            visit(child, childrenAbsorbed)
        }
    }

    roots.forEach { visit(it, absorbed = false) }
    return ParsedUiTree(elements, packages, truncated)
}

private fun parseBounds(s: String): Rect {
    val m = BOUNDS_REGEX.find(s) ?: return Rect()
    val (l, t, r, b) = m.destructured
    return Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
}

private fun String.cleanLabel(): String = replace('\n', ' ').trim().let {
    if (it.length > MAX_LABEL_CHARS) it.take(MAX_LABEL_CHARS) + "…" else it
}

internal fun parseVisiblePackagesFromWindowDump(dump: String, ownPackageName: String): Set<String> {
    val result = LinkedHashSet<String>()
    var currentPkg: String? = null
    for (line in dump.lineSequence()) {
        val headerMatch = WINDOW_HEADER_REGEX.find(line)
        if (headerMatch != null) {
            currentPkg = headerMatch.groupValues[1]
        }
        if (currentPkg != null && (line.contains("isOnScreen=true") || line.contains("mViewVisibility=0x0") && line.contains("mHasSurface=true"))) {
            if (currentPkg != ownPackageName && currentPkg != "com.android.systemui") {
                result += currentPkg
            }
        }
    }
    parseFocusedPackage(dump, ownPackageName)?.let(result::add)
    return result
}

private fun parseFocusedPackage(dump: String, ownPackageName: String): String? =
    FOCUS_PKG_REGEX.find(dump)?.groupValues?.getOrNull(1)?.takeIf {
        it != ownPackageName && it != "com.android.systemui"
    }
