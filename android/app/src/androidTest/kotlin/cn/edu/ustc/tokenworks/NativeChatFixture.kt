package cn.edu.ustc.tokenworks

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.enthusjast.tokenworks.ui.TokenWorksTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native accessibility driver. No Compose test owner, Espresso idler, shell input or real account. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal class NativeChatFixture private constructor(
    private val scenario: ActivityScenario<out ComponentActivity>,
    initialViewport: WorkspaceViewport,
    val dark: Boolean,
    val fontScale: Float,
) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val originalServiceInfo = automation.serviceInfo
    private val viewportState = mutableStateOf(initialViewport)
    private val pendingViewport = java.util.concurrent.atomic.AtomicReference<Pair<WorkspaceViewport, CountDownLatch>?>(null)
    val viewport: WorkspaceViewport get() = viewportState.value
    lateinit var activity: ComponentActivity
        private set
    @Volatile var density: Float = 1f
        private set

    init {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        scenario.onActivity { activity = it }
        instrumentation.runOnMainSync {
            activity.currentFocus?.clearFocus()
            activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
    }

    fun content(body: @Composable () -> Unit) {
        draw {
            activity.setContent {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val physicalDensity = LocalDensity.current.density
                    val current = viewportState.value
                    // Fit a real, fully drawn dp viewport into this device's window. This does not
                    // change wm density/font settings and does not claim to be a physical tablet.
                    val fitted = minOf(maxWidth.value * physicalDensity / current.widthDp,
                        maxHeight.value * physicalDensity / current.heightDp)
                    val config = android.content.res.Configuration(LocalConfiguration.current).apply {
                        screenWidthDp = current.widthDp
                        screenHeightDp = current.heightDp
                        fontScale = this@NativeChatFixture.fontScale
                    }
                    SideEffect {
                        density = fitted
                        pendingViewport.get()?.takeIf { it.first == current }?.second?.countDown()
                    }
                    CompositionLocalProvider(LocalDensity provides Density(fitted, fontScale),
                        LocalConfiguration provides config) {
                        TokenWorksTheme(darkTheme = dark) {
                            Box(Modifier.fillMaxSize().testTag("native-public-fixture")
                                .semantics {
                                    testTagsAsResourceId = true
                                    isTraversalGroup = true
                                    contentDescription = "公开测试画布"
                                }) { body() }
                        }
                    }
                }
            }
        }
        await("first rendered public fixture") { nodes -> nodes.any { it.tag("native-public-fixture") } }
        settle()
        hostWindowId = automation.rootInActiveWindow?.windowId
    }

    private var hostWindowId: Int? = null
    fun modalForeground(): Boolean = automation.rootInActiveWindow?.windowId?.let { it != hostWindowId } == true

    private fun settle() {
        automation.waitForIdle(100, 5_000)
        draw()
    }

    /** Completes on an actual decor draw, not an elapsed timer or a test-framework idle guess. */
    fun draw(change: () -> Unit = {}) {
        val drawn = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val view = activity.window.decorView
            if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated) {
                view.viewTreeObserver.registerFrameCommitCallback { drawn.countDown() }
            } else {
                val observer = object : ViewTreeObserver.OnDrawListener {
                    override fun onDraw() {
                        view.post {
                            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(this)
                            drawn.countDown()
                        }
                    }
                }
                view.viewTreeObserver.addOnDrawListener(observer)
            }
            change()
            view.postInvalidateOnAnimation()
        }
        check(drawn.await(15, TimeUnit.SECONDS)) { "No public activity draw; unlock the device and keep it foreground" }
    }

    fun resize(width: Int, height: Int = 960) {
        val target = WorkspaceViewport(width, height)
        if (target == viewport) { draw(); return }
        val committed = CountDownLatch(1)
        pendingViewport.set(target to committed)
        try {
            draw { viewportState.value = target }
            check(committed.await(15, TimeUnit.SECONDS)) { "Synthetic viewport composition did not commit: $target" }
            settle()
        } finally {
            pendingViewport.set(null)
        }
    }

    data class Node(
        val text: String, val description: String, val id: String, val bounds: Rect,
        val clickable: Boolean, val enabled: Boolean, val scrollable: Boolean, val state: String,
        val editable: Boolean,
    ) {
        fun tag(value: String) = id == value || id.endsWith(":id/$value")
        fun label(value: String) = text == value || description == value
        fun contains(value: String) = text.contains(value) || description.contains(value)
    }

    private fun visit(action: (AccessibilityNodeInfo) -> Unit) {
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        val roots = automation.windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(automation.rootInActiveWindow) }
        fun descend(node: AccessibilityNodeInfo) {
            try {
                if (node.packageName?.toString() == instrumentation.targetContext.packageName && node.isVisibleToUser) action(node)
                for (index in 0 until node.childCount) node.getChild(index)?.let(::descend)
            } finally {
                @Suppress("DEPRECATION")
                node.recycle()
            }
        }
        roots.forEach(::descend)
    }

    private fun node(info: AccessibilityNodeInfo) = Node(info.text?.toString().orEmpty(),
        info.contentDescription?.toString().orEmpty(), info.viewIdResourceName.orEmpty(),
        Rect().also(info::getBoundsInScreen), info.isClickable, info.isEnabled, info.isScrollable,
        info.stateDescription?.toString().orEmpty(), info.isEditable)

    fun nodes(): List<Node> = buildList { visit { add(node(it)) } }
    fun requireNode(selector: (Node) -> Boolean): Node = nodes().firstOrNull(selector)
        ?: error("Missing native accessibility node. Tree:\n${nodes().joinToString("\n")}")

    /** Icon descriptions may be on a non-action child; measure its real clickable ancestor. */
    fun actionNode(selector: (Node) -> Boolean): Node {
        await("action label ready") { it.any(selector) }
        var result: Node? = null
        visit { info ->
            if (result == null && selector(node(info))) {
                val label = node(info)
                var target: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(info)
                try {
                    while (target != null && !target!!.isClickable) {
                        val parent = target!!.parent
                        target!!.recycle()
                        target = parent
                    }
                    result = target?.let { node(it).copy(text = label.text, description = label.description, id = label.id) }
                } finally { target?.recycle() }
            }
        }
        return checkNotNull(result) { "No native action owner for selector. Tree:\n${nodes().joinToString("\n")}" }
    }
    fun absent(selector: (Node) -> Boolean) = check(nodes().none(selector)) { "Unexpected native accessibility node: ${nodes().filter(selector)}" }

    /** Accessibility notifications drive retries, including asynchronous Markdown projection. */
    fun await(reason: String, predicate: (List<Node>) -> Boolean) {
        val changed = java.util.concurrent.Semaphore(0)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        automation.setOnAccessibilityEventListener { event ->
            // UiAutomation may dispatch on the app main looper. Never query its node provider
            // synchronously from this callback; wake the instrumentation thread instead.
            changed.release()
        }
        try {
            while (!predicate(nodes())) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0 || !changed.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                    check(predicate(nodes())) { "Native state did not establish $reason. Tree:\n${nodes().joinToString("\n")}" }
                    break
                }
            }
        } finally {
            automation.setOnAccessibilityEventListener(null)
        }
    }

    fun act(action: Int, selector: (Node) -> Boolean, arguments: Bundle? = null) {
        await("native action owner ready") { it.any(selector) }
        var performed = false
        visit { info ->
            if (!performed && selector(node(info))) {
                var target: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(info)
                try {
                    while (target != null && (target!!.actionList.none { it.id == action } ||
                            action == AccessibilityNodeInfo.ACTION_CLICK && !target!!.isClickable)) {
                        val parent = target!!.parent
                        @Suppress("DEPRECATION")
                        target!!.recycle()
                        target = parent
                    }
                    performed = target?.performAction(action, arguments) == true
                } finally {
                    @Suppress("DEPRECATION")
                    target?.recycle()
                }
            }
        }
        check(performed) { "Native accessibility action $action was not accepted. Tree:\n${nodes().joinToString("\n")}" }
        settle()
    }

    fun click(label: String) = act(AccessibilityNodeInfo.ACTION_CLICK, { it.label(label) })
    fun clickTag(tag: String) = act(AccessibilityNodeInfo.ACTION_CLICK, { it.tag(tag) })
    fun setText(tag: String, text: String) = act(AccessibilityNodeInfo.ACTION_SET_TEXT, { it.tag(tag) }, Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    })
    fun back() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("input keyevent KEYCODE_BACK")).use { it.readBytes() }
        settle()
    }

    fun imeVisible(): Boolean = automation.windows.any {
        it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
    }

    fun enter() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("input keyevent KEYCODE_ENTER")).use { it.readBytes() }
        settle()
    }

    /** Each real scroll action settles a committed frame; clipped text bounds need not change. */
    fun scrollTo(selector: (Node) -> Boolean, forward: Boolean, limit: Int = 60) {
        repeat(limit) {
            val current = nodes()
            val viewport = current.firstOrNull { it.tag("chat-list") }?.bounds
            if (current.any { selector(it) && (viewport == null || Rect.intersects(it.bounds, viewport)) }) return
            act(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                { it.tag("chat-list") })
        }
        error("Native history scroll did not reach the requested original message: ${nodes()}")
    }

    fun capture(name: String, measurements: org.json.JSONObject = org.json.JSONObject()) =
        captureUiFixture(activity, name, viewport, density, fontScale, dark, measurements)

    override fun close() {
        try { scenario.close() } finally { automation.serviceInfo = originalServiceInfo }
    }

    companion object {
        fun launch(viewport: WorkspaceViewport = WorkspaceViewport(360, 800), dark: Boolean = false,
            fontScale: Float = 1f): NativeChatFixture {
            val scenario: ActivityScenario<out ComponentActivity> = if (fontScale >= 2f)
                ActivityScenario.launch(LargeTextUiFixtureActivity::class.java)
            else ActivityScenario.launch(ComponentActivity::class.java)
            return NativeChatFixture(scenario, viewport, dark, fontScale)
        }
    }
}
