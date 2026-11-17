package cn.edu.ustc.tokenworks

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class FilesOperation { IDLE, LOAD, UPLOAD, PREPARE_IMAGE, EXPORT }
data class FilesUiState(
    val visible: Boolean = false,
    val path: String = "",
    val preview: StagedFile? = null,
    val uploaded: List<UploadedAttachment> = emptyList(),
    val images: List<PreparedNativeImage> = emptyList(),
    val operation: FilesOperation = FilesOperation.IDLE,
    val progressBytes: Long = 0,
    val totalBytes: Long? = null,
    val error: String? = null,
    val exported: Boolean = false,
    val pickerActive: Boolean = false,
) { val busy get() = operation != FilesOperation.IDLE }

/** All resolver/picker access is injected. Closing never closes the shared authenticated transport. */
class FilesCoordinator(transport: DshTransport, privateDirectory: File, owner: CoroutineScope,
    private val boundSessionId: String? = null) : AutoCloseable {
    private val client = DshFilesClient(transport, privateDirectory)
    private val scope = CoroutineScope(SupervisorJob(owner.coroutineContext[Job]) + Dispatchers.IO)
    private val mutableState = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var imageSessionId: String? = boundSessionId
    private var closed = false

    @Synchronized fun open() { mutableState.value = mutableState.value.copy(visible = true) }
    @Synchronized fun pickerStarted() { mutableState.value = mutableState.value.copy(pickerActive = true) }
    @Synchronized fun pickerFinished() { mutableState.value = mutableState.value.copy(pickerActive = false) }
    fun load(path: String) = receive(path) { progress -> client.download(path, progress) }
    fun loadAttachment(sessionId: String, attachment: NativeImageReference) = receive("attachment") { progress -> client.attachment(sessionId, attachment, progress) }

    @Synchronized private fun receive(path: String, fetch: suspend ((Long) -> Unit) -> StagedFile) {
        cancel()
        mutableState.value.preview?.close()
        val id = generation
        mutableState.value = mutableState.value.copy(visible = true, path = path, preview = null,
            operation = FilesOperation.LOAD, progressBytes = 0, totalBytes = null, error = null, exported = false)
        job = scope.launch {
            var file: StagedFile? = null
            try {
                file = fetch { progress(id, it) }; ensureActive()
                synchronized(this@FilesCoordinator) {
                    if (id == generation) { mutableState.value = mutableState.value.copy(preview = file, operation = FilesOperation.IDLE); file = null }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { failed(id, error)
            } finally { file?.close() }
        }
    }

    @Synchronized fun upload(sessionId: String, name: String, sizeBytes: Long?, source: () -> InputStream) {
        if (mutableState.value.busy) return
        if (mutableState.value.uploaded.size >= 16) { mutableState.value = mutableState.value.copy(error = "files.too_many_attachments"); return }
        val id = generation
        mutableState.value = mutableState.value.copy(visible = true, operation = FilesOperation.UPLOAD,
            progressBytes = 0, totalBytes = sizeBytes, error = null)
        job = scope.launch {
            try {
                val uploaded = client.upload(sessionId, name, sizeBytes, source) { progress(id, it) }
                ensureActive()
                synchronized(this@FilesCoordinator) { if (id == generation) mutableState.value = mutableState.value.copy(
                    uploaded = mutableState.value.uploaded + uploaded, operation = FilesOperation.IDLE) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { failed(id, error) }
        }
    }

    /** Vision images are local inline prompt parts, never generic file upload receipts. */
    @Synchronized fun selectImage(sessionId: String, name: String, source: () -> InputStream) {
        if (closed || mutableState.value.busy) return
        if (sessionId.isBlank() || imageSessionId?.let { it != sessionId } == true) {
            mutableState.value = mutableState.value.copy(error = "files.image_session_changed"); return
        }
        if (mutableState.value.images.size >= NativeImagePart.MAX_IMAGES) {
            mutableState.value = mutableState.value.copy(error = "最多选择 4 张图片；未添加所选图片。"); return
        }
        imageSessionId = sessionId
        val id = generation
        mutableState.value = mutableState.value.copy(visible = true, operation = FilesOperation.PREPARE_IMAGE,
            progressBytes = 0, totalBytes = null, error = null)
        job = scope.launch {
            try {
                val context = currentCoroutineContext()
                val prepared = NativeImagePreprocessor().preprocess(name) {
                    object : java.io.FilterInputStream(source()) {
                        override fun read(): Int { context.ensureActive(); return super.read() }
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                            context.ensureActive(); return `in`.read(bytes, offset, length)
                        }
                    }
                }
                ensureActive()
                synchronized(this@FilesCoordinator) {
                    if (id == generation && !closed) {
                        val images = mutableState.value.images
                        if (images.sumOf { it.part.encodedBytes } + prepared.part.encodedBytes > NativeImagePart.MAX_BATCH_ENCODED_BYTES) {
                            mutableState.value = mutableState.value.copy(operation = FilesOperation.IDLE,
                                error = "图片总大小超过 1 MiB 编码限制；未添加所选图片。")
                        } else mutableState.value = mutableState.value.copy(images = images + prepared, operation = FilesOperation.IDLE)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: IllegalArgumentException) {
                synchronized(this@FilesCoordinator) { if (id == generation) mutableState.value = mutableState.value.copy(
                    operation = FilesOperation.IDLE, error = error.message ?: "无法安全处理图片；未添加所选图片。") }
            } catch (error: Exception) { failed(id, error) }
        }
    }

    @Synchronized fun imagesFor(sessionId: String): List<NativeImagePart> =
        if (!closed && imageSessionId == sessionId) mutableState.value.images.map { it.part } else emptyList()
    @Synchronized fun removeImage(part: NativeImagePart) {
        mutableState.value = mutableState.value.copy(images = mutableState.value.images.filterNot { it.part === part })
    }
    /** Consume only the captured immutable parts after their encrypted outbox commit. */
    @Synchronized fun consumeImages(parts: List<NativeImagePart>) {
        mutableState.value = mutableState.value.copy(images = mutableState.value.images.filterNot { image -> parts.any { it === image.part } })
    }
    @Synchronized fun clearImages() { mutableState.value = mutableState.value.copy(images = emptyList()) }

    /** Call only after the user confirms ACTION_CREATE_DOCUMENT, with a delete-on-abort target. */
    @Synchronized fun export(target: FileExportTarget) {
        val file = mutableState.value.preview
        if (mutableState.value.busy || file == null) { target.abort(); return }
        val id = generation
        mutableState.value = mutableState.value.copy(operation = FilesOperation.EXPORT, progressBytes = 0,
            totalBytes = file.sizeBytes, error = null, exported = false)
        job = scope.launch {
            try {
                client.export(file, target) { progress(id, it) }; ensureActive()
                synchronized(this@FilesCoordinator) { if (id == generation) mutableState.value = mutableState.value.copy(operation = FilesOperation.IDLE, exported = true) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { failed(id, error) }
        }
    }

    @Synchronized fun removeUpload(receiptId: String) { mutableState.value = mutableState.value.copy(uploaded = mutableState.value.uploaded.filterNot { it.receiptId == receiptId }) }
    @Synchronized fun cancel() {
        generation++; job?.cancel(); job = null
        mutableState.value = mutableState.value.copy(operation = FilesOperation.IDLE, progressBytes = 0, totalBytes = null)
    }
    @Synchronized fun dismiss() {
        cancel(); mutableState.value.preview?.close()
        mutableState.value = FilesUiState(uploaded = mutableState.value.uploaded, images = mutableState.value.images)
    }
    @Synchronized private fun progress(id: Long, count: Long) { if (id == generation) mutableState.value = mutableState.value.copy(progressBytes = count) }
    @Synchronized private fun failed(id: Long, error: Exception) {
        if (id == generation) mutableState.value = mutableState.value.copy(operation = FilesOperation.IDLE,
            error = DshRecoveryPolicy.classify(error).code)
    }
    @Synchronized override fun close() {
        closed = true; dismiss(); mutableState.value = FilesUiState(); scope.cancel()
    }
}
