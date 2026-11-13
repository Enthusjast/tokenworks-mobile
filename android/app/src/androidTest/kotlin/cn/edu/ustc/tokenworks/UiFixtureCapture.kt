package cn.edu.ustc.tokenworks

import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.io.File

/** Public synthetic ComponentActivity state only; never captures the secure production activity. */
internal fun captureUiFixture(rule: ComposeContentTestRule, name: String, dialog: Boolean = false, darkTheme: Boolean? = null) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val config = instrumentation.targetContext.resources.configuration
    val dark = darkTheme ?: ((config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
    val theme = if (dark) "dark" else "light"
    val bitmap = if (dialog) {
        val roots = rule.onAllNodes(isDialog() or isPopup()).fetchSemanticsNodes().ifEmpty {
            rule.onAllNodes(isRoot()).fetchSemanticsNodes()
        }
        var focused: View? = null
        rule.waitUntil(10_000) {
            focused = rule.runOnIdle {
                roots.firstNotNullOfOrNull { node ->
                    (node.root as? ViewRootForTest)?.view?.takeIf { it.hasWindowFocus() }
                }
            }
            focused != null
        }
        val publicFocusedWindow = rule.runOnIdle {
            val parameters = focused?.rootView?.layoutParams as? WindowManager.LayoutParams
            focused?.hasWindowFocus() == true && parameters != null &&
                parameters.flags and WindowManager.LayoutParams.FLAG_SECURE == 0
        }
        check(publicFocusedWindow) { "Native fixture capture requires a focused, non-secure Compose window" }
        checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    } else rule.onRoot().captureToImage().asAndroidBitmap()
    val directory = File(instrumentation.targetContext.filesDir, "ui-navigation-fixtures").apply { check(isDirectory || mkdirs()) }
    File(directory, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    File(directory, "$name.json").writeText(JSONObject().put("scene", name).put("synthetic", true)
        .put("density", rule.density.density).put("fontScale", rule.density.fontScale)
        .put("widthPx", bitmap.width).put("heightPx", bitmap.height)
        .put("api", android.os.Build.VERSION.SDK_INT).put("build", BuildConfig.BUILD_TYPE).put("version", BuildConfig.VERSION_NAME).put("theme", theme).toString(2))
}

/** Window pixels, not a semantics snapshot. This overload is for the two public debug hosts only. */
internal fun captureUiFixture(
    activity: android.app.Activity,
    name: String,
    viewport: WorkspaceViewport,
    density: Float,
    fontScale: Float,
    darkTheme: Boolean,
    measurements: JSONObject = JSONObject(),
): File {
    require(name.matches(Regex("[a-zA-Z0-9_-]+")))
    check(BuildConfig.DEBUG && (activity.javaClass == androidx.activity.ComponentActivity::class.java ||
        activity.javaClass == LargeTextUiFixtureActivity::class.java)) {
        "Native capture accepts only an exact public synthetic debug host, never MainActivity"
    }
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    lateinit var bitmap: android.graphics.Bitmap
    val copied = java.util.concurrent.CountDownLatch(1)
    var result = android.view.PixelCopy.ERROR_UNKNOWN
    instrumentation.runOnMainSync {
        check(!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()) {
            "Unlock and focus the public synthetic activity before capturing"
        }
        check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0) {
            "Secure windows must never be captured"
        }
        val decor = activity.window.decorView
        check(decor.isAttachedToWindow && decor.width > 0 && decor.height > 0)
        bitmap = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
        android.view.PixelCopy.request(activity.window, bitmap, { status ->
            result = status
            copied.countDown()
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    }
    check(copied.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Window PixelCopy did not complete" }
    check(result == android.view.PixelCopy.SUCCESS) { "Window PixelCopy failed: $result" }
    val directory = File(instrumentation.targetContext.filesDir, "ui-navigation-fixtures").apply {
        check(isDirectory || mkdirs())
    }
    val image = File(directory, "$name.png")
    try {
        image.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        File(directory, "$name.json").writeText(JSONObject()
            .put("scene", name).put("synthetic", true).put("capture", "Window.PixelCopy")
            .put("host", activity.javaClass.name).put("focused", true).put("secure", false)
            .put("theme", if (darkTheme) "dark" else "light").put("fontScale", fontScale)
            .put("density", density).put("deviceDensity", activity.resources.displayMetrics.density)
            .put("hostFontScale", activity.resources.configuration.fontScale)
            .put("viewportWidthDp", viewport.widthDp).put("viewportHeightDp", viewport.heightDp)
            .put("viewportWidthPx", viewport.widthDp * density).put("viewportHeightPx", viewport.heightDp * density)
            .put("widthPx", bitmap.width).put("heightPx", bitmap.height)
            .put("api", android.os.Build.VERSION.SDK_INT).put("device", android.os.Build.MODEL)
            .put("build", BuildConfig.BUILD_TYPE).put("version", BuildConfig.VERSION_NAME)
            .put("measurements", measurements).toString(2))
    } finally {
        bitmap.recycle()
    }
    return image
}
