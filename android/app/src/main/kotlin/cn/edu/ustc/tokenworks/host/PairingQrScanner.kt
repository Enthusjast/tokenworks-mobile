package cn.edu.ustc.tokenworks

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

internal class HostBindingQr private constructor(val id: String, val code: String) {
    companion object {
        fun parse(text: String): HostBindingQr? {
            if (text.length > 256) return null
            return runCatching {
                val payload = JsonDocument.parse(text).root
                payload.exactKeys("type", "id", "code")
                check(payload.string("type") == "tokenworks.binding.v1")
                HostBindingQr(Contract.uuid(payload.string("id")), Contract.nonce(payload.string("code")))
            }.getOrNull()
        }
    }
}

@Composable
internal fun PairingQrButton(enabled: Boolean = true, onPayload: (String) -> Boolean) {
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Int?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else failure = R.string.binding_qr_permission_denied
    }
    OutlinedButton({
        failure = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
        else permission.launch(Manifest.permission.CAMERA)
    }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.binding_scan_qr)) }
    failure?.let { TokenWorksStatusNotice(stringResource(it), isError = true) }
    if (scanning && enabled) {
        val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
        val accept by rememberUpdatedState(onPayload)
        AlertDialog(onDismissRequest = { scanning = false }, title = { Text(stringResource(R.string.binding_scan_qr)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                    Text(stringResource(R.string.binding_scan_instruction))
                    AndroidView(
                        factory = { CameraQrPreview(it, onCode = { payload ->
                            if (scanning) {
                                scanning = false
                                if (!accept(payload)) failure = R.string.binding_qr_unknown
                            }
                        }, onError = {
                            if (scanning) { scanning = false; failure = R.string.binding_qr_camera_failed }
                        }) },
                        onReset = null,
                        onRelease = CameraQrPreview::dispose,
                        update = { it.setRunning(lifecycle.isAtLeast(Lifecycle.State.RESUMED)) },
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                    )
                }
            }, confirmButton = { TextButton({ scanning = false }) { Text(stringResource(R.string.action_cancel)) } })
    }
}

/** Preview frames stay on this device, are never written to disk and are decoded at most five times per second. */
@Suppress("DEPRECATION")
private class CameraQrPreview(context: Context, val onCode: (String) -> Unit, val onError: () -> Unit) :
    SurfaceView(context), SurfaceHolder.Callback {
    private val thread = HandlerThread("pairing-qr").apply { start() }
    private val worker = Handler(thread.looper)
    @Volatile private var revision = 0
    private var running = false
    private var ready = false
    private var disposed = false
    private var camera: Camera? = null // Worker thread only.
    private val reader = QRCodeReader()

    init { holder.addCallback(this) }

    fun setRunning(value: Boolean) {
        if (running == value) return
        running = value
        updateCamera()
    }

    override fun surfaceCreated(holder: SurfaceHolder) { ready = true; updateCamera() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) { ready = false; updateCamera() }

    private fun updateCamera() {
        if (disposed) return
        val stamp = ++revision
        val start = ready && running
        val rotation = when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        worker.post {
            releaseCamera()
            if (!start || revision != stamp) return@post
            try {
                val info = Camera.CameraInfo()
                val back = (0 until Camera.getNumberOfCameras()).firstOrNull {
                    Camera.getCameraInfo(it, info)
                    info.facing == Camera.CameraInfo.CAMERA_FACING_BACK
                } ?: error("camera.unavailable")
                val active = Camera.open(back).also { camera = it }
                active.setDisplayOrientation((info.orientation - rotation + 360) % 360)
                active.setPreviewDisplay(holder)
                val settings = active.parameters
                val options = settings.supportedPreviewSizes.orEmpty().filter { it.width.toLong() * it.height <= 1_500_000 }
                options.maxByOrNull { it.width * it.height }?.let { settings.setPreviewSize(it.width, it.height) }
                if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in settings.supportedFocusModes.orEmpty()) {
                    settings.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                }
                active.parameters = settings
                val size = active.parameters.previewSize
                require(size.width.toLong() * size.height in 1..2_100_000)
                var lastDecode = -1_000L
                active.setPreviewCallback { bytes, _ ->
                    if (revision != stamp || bytes == null || bytes.size < size.width * size.height) return@setPreviewCallback
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastDecode < 200) return@setPreviewCallback
                    lastDecode = now
                    val source = PlanarYUVLuminanceSource(bytes, size.width, size.height, 0, 0, size.width, size.height, false)
                    val text = try {
                        reader.decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
                    } catch (_: ReaderException) { null }
                    if (!text.isNullOrEmpty() && text.length <= 16_384) {
                        releaseCamera()
                        post { if (revision == stamp) onCode(text) }
                    }
                }
                active.startPreview()
            } catch (_: Exception) {
                releaseCamera()
                post { if (revision == stamp) onError() }
            }
        }
    }

    private fun releaseCamera() {
        val active = camera ?: return
        camera = null
        runCatching { active.setPreviewCallback(null) }
        runCatching { active.stopPreview() }
        runCatching { active.release() }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        revision++
        holder.removeCallback(this)
        worker.post { releaseCamera(); thread.quitSafely() }
    }
}
