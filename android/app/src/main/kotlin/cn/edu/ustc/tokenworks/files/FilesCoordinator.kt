package cn.edu.ustc.tokenworks

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class FilesOperation { IDLE, LOAD, UPLOAD, PREPARE_IMAGE, BROWSE, EXPORT }

data class WorkspaceExportItem(val file: WorkspaceExportFile, val saved: Boolean = false)

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
    val workspaceListing: WorkspaceFileListing? = null,
    val workspaceExports: List<WorkspaceExportItem> = emptyList(),
    val exportCancelled: Boolean = false,
) { val busy get() = operation != FilesOperation.IDLE }

/** All resolver/picker access is injected. Closing never closes the shared authenticated transport. */
class FilesCoordinator(transport: DshTransport, private val privateDirectory: File, owner: CoroutineScope,
    private val boundSessionId: String? = null) : AutoCloseable {
    private val runtimeId = transport.runtimeId
    private var activeTransport: DshTransport? = transport
    private var client = DshFilesClient(transport, privateDirectory)
    private var workspace = boundSessionId?.let { DshWorkspaceFiles(transport, it) }
    private val scope = CoroutineScope(SupervisorJob(owner.coroutineContext[Job]) + Dispatchers.IO)
    private val mutableState = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var imageSessionId: String? = boundSessionId
    private var closed = false
    private var pendingWorkspaceSave: WorkspaceExportFile? = null
    private var pendingWorkspaceDestination: FileExportTarget? = null

    /** A document picker may outlive the foreground route, but never its session. */
    @Synchronized fun detachTransport() {
        activeTransport = null
        workspace = null
    }

    @Synchronized fun attachTransport(transport: DshTransport) {
        if (closed || transport.runtimeId != runtimeId) return
        if (activeTransport !== transport) {
            activeTransport = transport
            client = DshFilesClient(transport, privateDirectory)
            workspace = boundSessionId?.let { DshWorkspaceFiles(transport, it) }
        }
        pendingWorkspaceDestination?.let { destination ->
            pendingWorkspaceDestination = null
            finishWorkspaceSavePicker(destination)
        }
    }

    @Synchronized fun open() { mutableState.value = mutableState.value.copy(visible = true) }
    @Synchronized fun pickerStarted() { mutableState.value = mutableState.value.copy(pickerActive = true) }
    @Synchronized fun pickerFinished() { mutableState.value = mutableState.value.copy(pickerActive = false) }
    @Synchronized fun load(path: String) {
        val files = client
        receive(path) { progress -> files.download(path, progress) }
    }
    @Synchronized fun loadAttachment(sessionId: String, attachment: NativeImageReference) {
        val files = client
        receive("attachment") { progress -> files.attachment(sessionId, attachment, progress) }
    }

    @Synchronized private fun receive(path: String, fetch: suspend ((Long) -> Unit) -> StagedFile) {
        if (closed || mutableState.value.operation == FilesOperation.EXPORT || mutableState.value.pickerActive) return
        invalidate()
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
        if (closed || mutableState.value.busy || mutableState.value.pickerActive) return
        if (mutableState.value.uploaded.size >= 16) { mutableState.value = mutableState.value.copy(error = "files.too_many_attachments"); return }
        val id = generation
        val files = client
        mutableState.value = mutableState.value.copy(visible = true, operation = FilesOperation.UPLOAD,
            progressBytes = 0, totalBytes = sizeBytes, error = null)
        job = scope.launch {
            try {
                val uploaded = files.upload(sessionId, name, sizeBytes, source) { progress(id, it) }
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

    @Synchronized fun browseWorkspace(parts: List<String> = emptyList()) {
        val client = workspace ?: return
        if (closed || mutableState.value.busy || mutableState.value.pickerActive) return
        val id = generation
        mutableState.value = mutableState.value.copy(operation = FilesOperation.BROWSE, error = null, exported = false)
        job = scope.launch {
            try {
                val listing = client.list(parts)
                ensureActive()
                synchronized(this@FilesCoordinator) {
                    if (id == generation) mutableState.value = mutableState.value.copy(
                        workspaceListing = listing, operation = FilesOperation.IDLE,
                    )
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failed(id, error) }
        }
    }

    @Synchronized fun addWorkspaceExport(file: WorkspaceExportFile) {
        val current = mutableState.value
        if (closed || current.busy || current.pickerActive || file !in current.workspaceListing?.entries.orEmpty()) return
        if (file.sizeBytes > DshWorkspaceFiles.MAX_FILE_BYTES) return
        val previous = current.workspaceExports.firstOrNull { it.file.parts == file.parts }
        if (previous?.file == file) return
        if (previous == null && current.workspaceExports.size >= 32) {
            mutableState.value = current.copy(error = "files.export_list_full")
            return
        }
        mutableState.value = current.copy(
            workspaceExports = current.workspaceExports.filterNot { it === previous } + WorkspaceExportItem(file),
            error = null, exported = false,
        )
    }

    @Synchronized fun removeWorkspaceExport(file: WorkspaceExportFile) {
        val current = mutableState.value
        if (current.busy || current.pickerActive) return
        mutableState.value = current.copy(workspaceExports = current.workspaceExports.filterNot { it.file == file })
    }

    @Synchronized fun startWorkspaceSavePicker(file: WorkspaceExportFile): Boolean {
        val current = mutableState.value
        if (closed || workspace == null || current.busy || current.pickerActive || current.workspaceExports.none { it.file == file }) return false
        pendingWorkspaceSave = file
        mutableState.value = current.copy(pickerActive = true, error = null, exported = false, exportCancelled = false)
        return true
    }

    @Synchronized fun finishWorkspaceSavePicker(target: FileExportTarget?) {
        val file = pendingWorkspaceSave
        val client = workspace
        // ActivityResult can run before onStart reconnects the foreground route.
        if (target != null && file != null && !closed && client == null && activeTransport == null) {
            if (pendingWorkspaceDestination == null) pendingWorkspaceDestination = target else rejectTarget(target)
            return
        }
        clearWorkspacePicker()
        if (target == null) return
        if (closed || client == null || file == null || mutableState.value.busy) {
            rejectTarget(target)
            return
        }
        save(target, file.sizeBytes, file) { destination, report -> client.save(file, destination, report) }
    }

    private fun clearWorkspacePicker() {
        if (pendingWorkspaceSave != null) pickerFinished()
        pendingWorkspaceSave = null
        val abandoned = pendingWorkspaceDestination
        pendingWorkspaceDestination = null
        abandoned?.let(::rejectTarget)
    }

    /** Call only after the user confirms ACTION_CREATE_DOCUMENT, with a delete-on-abort target. */
    @Synchronized fun export(target: FileExportTarget) {
        val file = mutableState.value.preview
        if (closed || mutableState.value.busy || file == null) {
            rejectTarget(target)
            return
        }
        val files = client
        save(target, file.sizeBytes, null) { destination, report -> files.export(file, destination, report) }
    }

    // Even cancellation before dispatch must run the cleanup for an already-created document.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun rejectTarget(target: FileExportTarget) {
        val id = generation
        scope.launch(start = CoroutineStart.ATOMIC) {
            try { target.abort() }
            catch (_: Exception) {
                synchronized(this@FilesCoordinator) {
                    if (id == generation && !closed) mutableState.value = mutableState.value.copy(error = "files.destination_cleanup_failed")
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun save(target: FileExportTarget, size: Long, workspaceFile: WorkspaceExportFile?,
                     transfer: suspend (FileExportTarget, (Long) -> Unit) -> Unit) {
        val id = generation
        mutableState.value = mutableState.value.copy(operation = FilesOperation.EXPORT, progressBytes = 0,
            totalBytes = size, error = null, exported = false, exportCancelled = false)
        job = scope.launch(start = CoroutineStart.ATOMIC) {
            val context = currentCoroutineContext()
            val destination = object : FileExportTarget by target {
                override fun commit() = synchronized(this@FilesCoordinator) {
                    context.ensureActive()
                    if (id != generation || closed) throw CancellationException()
                    target.commit()
                    val current = mutableState.value
                    mutableState.value = current.copy(operation = FilesOperation.IDLE, exported = true,
                        workspaceExports = if (workspaceFile == null) current.workspaceExports else current.workspaceExports.map {
                            if (it.file == workspaceFile) it.copy(saved = true) else it
                        })
                }
            }
            try {
                transfer(destination) { progress(id, it) }
            } catch (cancelled: CancellationException) {
                synchronized(this@FilesCoordinator) {
                    if (id == generation) mutableState.value = mutableState.value.copy(
                        operation = FilesOperation.IDLE, progressBytes = 0, totalBytes = null, exportCancelled = true,
                    )
                }
                throw cancelled
            } catch (error: Exception) { failed(id, error) }
        }
    }

    @Synchronized fun removeUpload(receiptId: String) { mutableState.value = mutableState.value.copy(uploaded = mutableState.value.uploaded.filterNot { it.receiptId == receiptId }) }
    @Synchronized fun cancel() {
        if (mutableState.value.operation == FilesOperation.EXPORT) {
            job?.cancel()
            return
        }
        invalidate()
        clearWorkspacePicker()
        mutableState.value = mutableState.value.copy(operation = FilesOperation.IDLE, progressBytes = 0, totalBytes = null)
    }
    private fun invalidate() {
        generation++
        job?.cancel()
        job = null
    }
    @Synchronized fun dismiss() {
        invalidate()
        clearWorkspacePicker()
        mutableState.value.preview?.close()
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
