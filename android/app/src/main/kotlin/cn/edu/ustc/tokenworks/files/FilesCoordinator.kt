package cn.edu.ustc.tokenworks

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class FilesOperation { IDLE, LOAD, UPLOAD, PREPARE_IMAGE, BROWSE, EXPORT }

data class WorkspaceExportItem(val file: WorkspaceExportFile, val saved: Boolean = false)

data class FilesImageGallery internal constructor(val references: List<NativeImageReference>, val selectedIndex: Int)
enum class FileUploadPhase { PREPARING, SENDING }

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
    val focusedPreview: Boolean = false,
    val imageGallery: FilesImageGallery? = null,
    val uploadPhase: FileUploadPhase? = null,
    val uploadCancelled: Boolean = false,
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
    private var pendingPreviewSave: StagedFile? = null
    private data class PickedUpload(val sessionId: String, val name: String, val sizeBytes: Long?, val source: () -> InputStream)
    private var pendingUpload: PickedUpload? = null

    /** A document picker may outlive the foreground route, but never its session. */
    @Synchronized fun detachTransport() {
        activeTransport = null
        workspace = null
        val current = mutableState.value
        if (current.operation == FilesOperation.LOAD || (current.focusedPreview && !current.pickerActive && !current.busy)) clearPreview()
        if (current.operation == FilesOperation.UPLOAD) cancel()
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
        pendingUpload?.let { picked ->
            pendingUpload = null
            pickerFinished()
            upload(picked.sessionId, picked.name, picked.sizeBytes, picked.source)
        }
    }

    @Synchronized fun open() { mutableState.value = mutableState.value.copy(visible = true) }
    @Synchronized fun pickerStarted() { mutableState.value = mutableState.value.copy(pickerActive = true) }
    @Synchronized fun pickerFinished() { mutableState.value = mutableState.value.copy(pickerActive = false) }
    @Synchronized internal fun startAttachmentPicker(): Boolean {
        val current = mutableState.value
        if (closed || current.busy || current.pickerActive) return false
        if (current.uploaded.size >= 16) {
            mutableState.value = current.copy(error = "files.too_many_attachments")
            return false
        }
        mutableState.value = current.copy(pickerActive = true, error = null, uploadCancelled = false)
        return true
    }
    @Synchronized fun load(path: String) {
        val files = client
        receive(path) { progress -> files.download(path, progress) }
    }
    @Synchronized fun loadAttachment(sessionId: String, attachment: NativeImageReference) {
        if (boundSessionId == null) {
            val files = client
            receive(attachment.name.orEmpty(), totalBytes = attachment.bytes) { progress -> files.attachment(sessionId, attachment, progress) }
        } else openImageGallery(sessionId, listOf(attachment), 0)
    }

    @Synchronized internal fun openLinkedFile(sessionId: String, link: FilesPreviewLink): Boolean {
        if (!isBoundTo(sessionId) || mutableState.value.busy) return false
        val files = client
        return receive(link.name, focused = true) { progress -> files.preview(link.path, progress) }
    }

    @Synchronized fun openImageGallery(sessionId: String, images: List<NativeImageReference>, initialIndex: Int): Boolean {
        if (!isBoundTo(sessionId) || mutableState.value.busy || images.size !in 1..MAX_GALLERY_IMAGES || initialIndex !in images.indices) return false
        return loadGalleryPage(sessionId, FilesImageGallery(images.toList(), initialIndex))
    }

    @Synchronized internal fun selectGalleryImage(index: Int) {
        val current = mutableState.value
        val gallery = current.imageGallery ?: return
        val sessionId = boundSessionId ?: return
        if (index !in gallery.references.indices || index == gallery.selectedIndex || current.busy && current.operation != FilesOperation.LOAD) return
        loadGalleryPage(sessionId, gallery.copy(selectedIndex = index))
    }

    @Synchronized internal fun retryGalleryImage() {
        val current = mutableState.value
        val gallery = current.imageGallery ?: return
        val sessionId = boundSessionId ?: return
        if (!current.busy && current.preview == null) loadGalleryPage(sessionId, gallery)
    }

    private fun isBoundTo(sessionId: String) = !closed && activeTransport != null && boundSessionId == sessionId

    private fun loadGalleryPage(sessionId: String, gallery: FilesImageGallery): Boolean {
        val files = client
        val reference = gallery.references[gallery.selectedIndex]
        return receive(reference.name.orEmpty(), focused = true, gallery = gallery, totalBytes = reference.bytes) { progress ->
            files.attachment(sessionId, reference, progress)
        }
    }

    @Synchronized internal fun previewWorkspaceFile(file: WorkspaceExportFile): Boolean {
        val client = workspace ?: return false
        val current = mutableState.value
        if (current.busy || file !in current.workspaceListing?.entries.orEmpty() || file.sizeBytes > FilePreview.MAX_PREVIEW_BYTES) return false
        return receive(file.name, focused = true, totalBytes = file.sizeBytes) { progress -> client.preview(file, privateDirectory, progress) }
    }

    @Synchronized internal fun closeFocusedPreview(): Boolean {
        val current = mutableState.value
        if (!current.focusedPreview || current.pickerActive || current.operation == FilesOperation.EXPORT) return false
        clearPreview()
        return true
    }

    private fun clearPreview() {
        invalidate()
        val current = mutableState.value
        current.preview?.close()
        mutableState.value = current.copy(preview = null, path = "", focusedPreview = false, imageGallery = null,
            operation = FilesOperation.IDLE, progressBytes = 0, totalBytes = null, error = null, exported = false, exportCancelled = false)
    }

    private fun receive(path: String, focused: Boolean = false, gallery: FilesImageGallery? = null,
                        totalBytes: Long? = null, fetch: suspend ((Long) -> Unit) -> StagedFile): Boolean {
        val current = mutableState.value
        if (closed || activeTransport == null || current.busy && current.operation != FilesOperation.LOAD || current.pickerActive) return false
        invalidate()
        current.preview?.close()
        val id = generation
        mutableState.value = current.copy(visible = true, path = path, preview = null, focusedPreview = focused, imageGallery = gallery,
            operation = FilesOperation.LOAD, progressBytes = 0, totalBytes = totalBytes, error = null, exported = false, exportCancelled = false)
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
        return true
    }

    @Synchronized fun upload(sessionId: String, name: String, sizeBytes: Long?, source: () -> InputStream) {
        if (closed || boundSessionId?.let { it != sessionId } == true || mutableState.value.busy || mutableState.value.pickerActive) return
        if (mutableState.value.uploaded.size >= 16) { mutableState.value = mutableState.value.copy(error = "files.too_many_attachments"); return }
        if (activeTransport == null) {
            pendingUpload = PickedUpload(sessionId, name, sizeBytes, source)
            pickerStarted()
            return
        }
        val id = generation
        val files = client
        mutableState.value = mutableState.value.copy(visible = true, operation = FilesOperation.UPLOAD,
            progressBytes = 0, totalBytes = sizeBytes, error = null, uploadPhase = FileUploadPhase.PREPARING, uploadCancelled = false)
        job = scope.launch {
            try {
                val uploaded = files.upload(sessionId, name, sizeBytes, source,
                    onPreparing = { progress(id, it) }, onProgress = { progress(id, it) }, onUploadStarted = { actualSize ->
                        synchronized(this@FilesCoordinator) { if (id == generation) mutableState.value = mutableState.value.copy(
                            uploadPhase = FileUploadPhase.SENDING, progressBytes = 0, totalBytes = actualSize) }
                    })
                ensureActive()
                synchronized(this@FilesCoordinator) { if (id == generation) mutableState.value = mutableState.value.copy(
                    uploaded = mutableState.value.uploaded + uploaded, operation = FilesOperation.IDLE, uploadPhase = null) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                failed(id, if (error is RemoteRequestException && error.code == "files.too_large")
                    RemoteRequestException("files.upload_too_large") else error)
            }
        }
    }

    /** Vision images are local inline prompt parts, never generic file upload receipts. */
    @Synchronized fun selectImage(sessionId: String, name: String, source: () -> InputStream) {
        if (closed || mutableState.value.busy) return
        if (sessionId.isBlank() || imageSessionId?.let { it != sessionId } == true) {
            mutableState.value = mutableState.value.copy(error = "files.image_session_changed"); return
        }
        if (mutableState.value.images.size >= NativeImagePart.MAX_IMAGES) {
            mutableState.value = mutableState.value.copy(error = "files.image_count_limit"); return
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
                                error = "files.image_batch_limit")
                        } else mutableState.value = mutableState.value.copy(images = images + prepared, operation = FilesOperation.IDLE)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: IllegalArgumentException) {
                synchronized(this@FilesCoordinator) { if (id == generation) mutableState.value = mutableState.value.copy(
                    operation = FilesOperation.IDLE, error = (error as? NativeImagePreparationException)?.code ?: "files.image_prepare_failed") }
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
        val requested = parts.toList()
        val listing = mutableState.value.workspaceListing
        val here = listing?.parts.orEmpty()
        val ancestorOrCurrent = requested.size <= here.size && here.take(requested.size) == requested
        if (!ancestorOrCurrent && listing?.entries.orEmpty().none { it is WorkspaceFileDirectory && it.parts == requested }) return
        val id = generation
        mutableState.value = mutableState.value.copy(operation = FilesOperation.BROWSE, error = null, exported = false)
        job = scope.launch {
            try {
                val listing = client.list(requested)
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

    @Synchronized internal fun startPreviewSavePicker(file: StagedFile): Boolean {
        val current = mutableState.value
        if (closed || current.busy || current.pickerActive || current.preview !== file) return false
        pendingPreviewSave = file
        mutableState.value = current.copy(pickerActive = true, error = null, exported = false, exportCancelled = false)
        return true
    }

    @Synchronized internal fun finishPreviewSavePicker(target: FileExportTarget?) {
        val file = pendingPreviewSave
        if (file != null) pickerFinished()
        pendingPreviewSave = null
        if (target != null) {
            if (!closed && file != null && file === mutableState.value.preview) export(target) else rejectTarget(target)
        }
    }

    private fun clearPreviewAndUploadPickers() {
        if (pendingPreviewSave != null || pendingUpload != null) pickerFinished()
        pendingPreviewSave = null
        pendingUpload = null
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
        val wasUploading = mutableState.value.operation == FilesOperation.UPLOAD || pendingUpload != null
        invalidate()
        clearWorkspacePicker()
        clearPreviewAndUploadPickers()
        mutableState.value = mutableState.value.copy(operation = FilesOperation.IDLE, progressBytes = 0, totalBytes = null,
            uploadPhase = null, uploadCancelled = wasUploading, error = null)
    }
    private fun invalidate() {
        generation++
        job?.cancel()
        job = null
    }
    @Synchronized fun dismiss() {
        invalidate()
        clearWorkspacePicker()
        clearPreviewAndUploadPickers()
        mutableState.value.preview?.close()
        mutableState.value = FilesUiState(uploaded = mutableState.value.uploaded, images = mutableState.value.images)
    }
    @Synchronized private fun progress(id: Long, count: Long) { if (id == generation) mutableState.value = mutableState.value.copy(progressBytes = count) }
    @Synchronized private fun failed(id: Long, error: Exception) {
        if (id == generation) mutableState.value = mutableState.value.copy(operation = FilesOperation.IDLE,
            uploadPhase = null, error = DshRecoveryPolicy.classify(error).code)
    }
    @Synchronized override fun close() {
        closed = true; dismiss(); mutableState.value = FilesUiState(); scope.cancel()
    }
}
