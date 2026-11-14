package cn.edu.ustc.tokenworks

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * N-3: file preview session. Fetches one file through the exact route under
 * the runtime lease and exposes decodeable state for the UI.
 */
enum class PreviewPhase { IDLE, LOADING, READY, FAILED }

class FilePreviewWatcher(private val api: RemoteApi, owner: CoroutineScope) {
    private val scope = CoroutineScope(SupervisorJob(owner.coroutineContext[Job]) + Dispatchers.Default)
    private val mutableState = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = mutableState.asStateFlow()
    private var job: Job? = null

    data class PreviewState(val phase: PreviewPhase = PreviewPhase.IDLE, val path: String = "", val bytes: ByteArray = ByteArray(0),
        val mediaType: String = "text/plain", val error: String? = null) {
        val previewable get() = FilePreview.previewable(mediaType)
        val asText: String get() = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
        override fun toString() = "PreviewState($phase, ${bytes.size}B)"
    }

    /** Fetches and holds one preview; a new call replaces the previous one. */
    fun load(api_lease: () -> Pair<String, DshLease>, path: String) {
        job?.cancel()
        mutableState.value = PreviewState(PreviewPhase.LOADING, path)
        job = scope.launch {
            try {
                val (runtimeId, lease) = api_lease()
                val preview = FilePreview()
                val bytes = preview.fetch(api, runtimeId, lease, path)
                val mediaType = guessMediaType(path)
                mutableState.value = PreviewState(PreviewPhase.READY, path, bytes, mediaType)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = PreviewState(PreviewPhase.FAILED, path,
                    error = if (error is RemoteRequestException) error.code else "preview.failed")
            }
        }
    }

    fun dismiss() {
        job?.cancel(); job = null
        mutableState.value = PreviewState()
    }

    private fun guessMediaType(path: String): String = when {
        path.endsWith(".png") -> "image/png"; path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".gif") -> "image/gif"; path.endsWith(".webp") -> "image/webp"
        path.endsWith(".pdf") -> "application/pdf"; path.endsWith(".json") -> "application/json"
        else -> "text/plain"
    }
}
