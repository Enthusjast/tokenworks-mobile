package cn.edu.ustc.tokenworks

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PreviewPhase { IDLE, LOADING, READY, FAILED }

/** Owns disk staging; no binary body is retained in a reactive state object. */
class FilePreviewWatcher(transport: DshTransport, directory: File, owner: CoroutineScope) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob(owner.coroutineContext[Job]) + Dispatchers.IO)
    private val client = DshFilesClient(transport, directory)
    private val mutableState = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = mutableState.asStateFlow()
    private var job: Job? = null
    private var current: StagedFile? = null
    private var generation = 0L

    data class PreviewState(val phase: PreviewPhase = PreviewPhase.IDLE, val path: String = "",
        val file: StagedFile? = null, val progressBytes: Long = 0, val error: String? = null) {
        val previewable get() = file?.let { FilePreview.previewable(it.mediaType) } == true
    }

    fun load(path: String) = start(path) { progress -> client.download(path, progress) }
    fun loadAttachment(sessionId: String, attachment: NativeImageReference) = start("attachment") { progress -> client.attachment(sessionId, attachment, progress) }

    @Synchronized private fun start(path: String, receive: suspend ((Long) -> Unit) -> StagedFile) {
        dismiss()
        val id = generation
        mutableState.value = PreviewState(PreviewPhase.LOADING, path)
        job = scope.launch {
            var staged: StagedFile? = null
            try {
                staged = receive { count -> synchronized(this@FilePreviewWatcher) {
                    if (generation == id) mutableState.value = PreviewState(PreviewPhase.LOADING, path, progressBytes = count)
                } }
                ensureActive()
                synchronized(this@FilePreviewWatcher) {
                    if (generation == id) {
                        current = staged
                        mutableState.value = PreviewState(PreviewPhase.READY, path, staged)
                        staged = null
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                synchronized(this@FilePreviewWatcher) { if (generation == id) mutableState.value = PreviewState(PreviewPhase.FAILED, path,
                    error = (error as? RemoteRequestException)?.code ?: "files.load_failed") }
            } finally { staged?.close() }
        }
    }

    @Synchronized fun dismiss() {
        generation++; job?.cancel(); job = null; current?.close(); current = null
        mutableState.value = PreviewState()
    }
    override fun close() { dismiss(); scope.cancel() }
}
