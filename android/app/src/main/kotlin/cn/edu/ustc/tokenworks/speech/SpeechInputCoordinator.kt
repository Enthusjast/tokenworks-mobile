package cn.edu.ustc.tokenworks

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

internal data class SpeechInputState(
    val listening: Boolean = false,
    val finishing: Boolean = false,
    val awaitingPermission: Boolean = false,
    val text: String = "",
    val error: String? = null,
)

/** The Android recognizer is the only platform boundary; no audio leaves this component via app code. */
internal interface SpeechRecognitionPlatform {
    val onDeviceAvailable: Boolean
    val systemAvailable: Boolean
    fun create(onDevice: Boolean): SpeechRecognitionSession
}

internal interface SpeechRecognitionSession {
    fun attachListener(listener: RecognitionListener)
    fun start(intent: Intent)
    fun stop()
    fun cancel()
    fun destroy()
}

private class AndroidSpeechRecognitionPlatform(context: Context) : SpeechRecognitionPlatform {
    private val context = context.applicationContext
    override val onDeviceAvailable = Build.VERSION.SDK_INT >= 31 &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(this.context)
    override val systemAvailable = SpeechRecognizer.isRecognitionAvailable(this.context)

    override fun create(onDevice: Boolean): SpeechRecognitionSession {
        // Never silently fall back from an on-device recognizer to a possibly networked provider.
        val recognizer = if (onDevice && Build.VERSION.SDK_INT >= 31)
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        return object : SpeechRecognitionSession {
            override fun attachListener(listener: RecognitionListener) = recognizer.setRecognitionListener(listener)
            override fun start(intent: Intent) = recognizer.startListening(intent)
            override fun stop() = recognizer.stopListening()
            override fun cancel() = recognizer.cancel()
            override fun destroy() = recognizer.destroy()
        }
    }
}

/** Main-thread, sheet-scoped ownership. A permission grant is not a new request to use the microphone. */
internal class SpeechInputCoordinator(private val platform: SpeechRecognitionPlatform) : AutoCloseable {
    constructor(context: Context) : this(AndroidSpeechRecognitionPlatform(context))

    val onDeviceAvailable get() = platform.onDeviceAvailable
    val systemAvailable get() = platform.systemAvailable
    private val mutable = MutableStateFlow(SpeechInputState())
    val state: StateFlow<SpeechInputState> = mutable.asStateFlow()
    private var recognizer: SpeechRecognitionSession? = null
    private var generation = 0L
    private var closed = false
    private var foreground = false
    private var resumed = false
    private var permissionInFlight = false
    private var pendingSystemConsent: Boolean? = null
    private var grantedSystemConsent: Boolean? = null

    fun setForeground(value: Boolean, resumed: Boolean = value) {
        if (closed) return
        foreground = value
        this.resumed = value && resumed
        if (!value || (!this.resumed && mutable.value.listening)) {
            cancel()
        } else if (this.resumed) {
            // Android can deliver a permission result before ON_RESUME. Wait, but never survive ON_STOP.
            val consent = grantedSystemConsent
            grantedSystemConsent = null
            if (consent != null) start(consent)
        }
    }

    /** Only called in response to Start. Consent is captured for this attempt, not for later starts. */
    fun requestStart(hasPermission: Boolean, allowSystemProvider: Boolean, requestPermission: () -> Unit) {
        if (closed || !foreground || !resumed || mutable.value.listening || mutable.value.awaitingPermission) return
        if (!canUseProvider(allowSystemProvider)) return
        if (hasPermission) {
            start(allowSystemProvider)
            return
        }
        pendingSystemConsent = allowSystemProvider
        permissionInFlight = true
        mutable.value = mutable.value.copy(awaitingPermission = true, error = null)
        try {
            requestPermission()
        } catch (_: Exception) {
            if (!permissionInFlight) return
            permissionInFlight = false
            pendingSystemConsent = null
            mutable.value = mutable.value.copy(awaitingPermission = false, error = "无法请求麦克风权限；没有开始识别。")
        }
    }

    fun onPermissionResult(granted: Boolean) {
        if (!permissionInFlight) return
        permissionInFlight = false
        val consent = pendingSystemConsent
        pendingSystemConsent = null
        if (closed) return
        mutable.value = mutable.value.copy(awaitingPermission = false)
        // Cancel, background and disposal invalidate the original user action, even if permission is granted.
        if (consent == null || !foreground) return
        if (granted) {
            if (resumed) start(consent)
            else {
                grantedSystemConsent = consent
                mutable.value = mutable.value.copy(awaitingPermission = true)
            }
        } else mutable.value = mutable.value.copy(error = "麦克风权限未获准。没有开始识别；可在系统设置中管理权限。")
    }

    private fun canUseProvider(allowSystemProvider: Boolean): Boolean {
        if (onDeviceAvailable || (allowSystemProvider && systemAvailable)) return true
        mutable.value = mutable.value.copy(error = if (systemAvailable)
            "此设备没有可用的本机识别服务。使用系统语音服务前需要本次明确同意。"
        else "没有可用的语音识别服务；仍可使用键盘输入。")
        return false
    }

    private fun start(allowSystemProvider: Boolean) {
        if (closed || !foreground || !resumed || !canUseProvider(allowSystemProvider)) return
        retire()
        val id = generation
        mutable.value = SpeechInputState(listening = true)
        try {
            val engine = platform.create(onDeviceAvailable)
            recognizer = engine
            engine.attachListener(object : RecognitionListener {
                private fun live() = !closed && foreground && generation == id
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) { /* Never retain, log or forward audio. */ }
                override fun onEndOfSpeech() {
                    if (live()) mutable.value = mutable.value.copy(finishing = true)
                }
                override fun onError(error: Int) {
                    if (!live()) return
                    mutable.value = mutable.value.copy(listening = false, finishing = false, error = when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要麦克风权限；可在系统设置中撤销。"
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有识别到文字，可重新开始。"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音服务网络不可用；未向会话发送任何内容。"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音服务正在使用中，请稍后重试。"
                        else -> "语音识别失败（$error）；未向会话发送任何内容。"
                    })
                    retire()
                }
                override fun onResults(results: Bundle?) {
                    if (!live()) return
                    val text = results.recognizedText()
                    mutable.value = SpeechInputState(text = boundedSpeechText(text), error = textLimitError(text))
                    retire()
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (!live()) return
                    val text = partialResults.recognizedText()
                    mutable.value = mutable.value.copy(text = boundedSpeechText(text), error = textLimitError(text))
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            engine.start(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Only the dedicated on-device recognizer guarantees offline operation. This is a hint otherwise.
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        } catch (_: Exception) {
            if (closed || generation != id) return
            retire()
            mutable.value = mutable.value.copy(listening = false, finishing = false,
                error = "无法启动语音服务；请检查麦克风权限和系统服务。")
        }
    }

    /** Stop requests a final result; cancel/close instead invalidate all outstanding callbacks. */
    fun stop() {
        if (closed || !mutable.value.listening || mutable.value.finishing) return
        mutable.value = mutable.value.copy(finishing = true)
        val id = generation
        try {
            recognizer?.stop()
        } catch (_: Exception) {
            if (closed || generation != id) return
            retire()
            mutable.value = mutable.value.copy(listening = false, finishing = false, error = "无法结束识别；已释放麦克风，可重新开始。")
        }
    }

    fun cancel() {
        if (closed) return
        pendingSystemConsent = null
        grantedSystemConsent = null
        retire()
        // Keep the launch occupied until its original result arrives; it must not satisfy a newer request.
        mutable.value = SpeechInputState(awaitingPermission = permissionInFlight)
    }

    fun editText(text: String) {
        if (!closed && !mutable.value.listening)
            mutable.value = mutable.value.copy(text = boundedSpeechText(text), error = textLimitError(text))
    }

    private fun retire() {
        generation++
        val engine = recognizer
        recognizer = null
        // Invalidate first: even synchronous callbacks during cleanup belong to the retired session.
        if (engine != null) {
            runCatching { engine.cancel() }
            runCatching { engine.destroy() }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        foreground = false
        resumed = false
        pendingSystemConsent = null
        grantedSystemConsent = null
        retire()
        mutable.value = SpeechInputState()
    }
}

private const val MAX_SPEECH_TEXT_LENGTH = 32_768

private fun Bundle?.recognizedText(): String =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

private fun textLimitError(text: String): String? =
    if (text.length > MAX_SPEECH_TEXT_LENGTH) "文字过长，已安全截断至 32768 字符以内。" else null

private fun boundedSpeechText(text: String): String {
    if (text.length <= MAX_SPEECH_TEXT_LENGTH) return text
    var end = MAX_SPEECH_TEXT_LENGTH
    if (Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
    return text.substring(0, end)
}
