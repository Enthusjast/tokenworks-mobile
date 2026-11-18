package cn.edu.ustc.tokenworks

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream

@Composable
internal fun FilesScreen(coordinator: FilesCoordinator, sessionId: String, sentImages: List<NativeImageReference> = emptyList(),
    pickAttachmentOnOpen: Boolean = false, onAttachmentPickerRequestConsumed: () -> Unit = {},
    pickImageOnOpen: Boolean = false, onImagePickerRequestConsumed: () -> Unit = {}, onBack: () -> Unit) {
    val state by coordinator.state.collectAsState()
    val context = LocalContext.current
    var pane by rememberSaveable(sessionId) { mutableStateOf(FilesPane.ATTACHMENTS) }
    var returnToPane by rememberSaveable(sessionId) { mutableStateOf(false) }
    var attachmentPickerHandled by rememberSaveable(sessionId) { mutableStateOf(false) }
    var imagePickerHandled by rememberSaveable(sessionId) { mutableStateOf(false) }
    LaunchedEffect(pickAttachmentOnOpen) { if (!pickAttachmentOnOpen) attachmentPickerHandled = false }
    LaunchedEffect(pickImageOnOpen) { if (!pickImageOnOpen) imagePickerHandled = false }
    val requested = pickAttachmentOnOpen && !attachmentPickerHandled
    val imageRequested = pickImageOnOpen && !imagePickerHandled
    val currentPane = if (requested || imageRequested) FilesPane.ATTACHMENTS else pane
    var pickerError by remember(coordinator) { mutableStateOf(false) }
    val saveWorkspaceFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        coordinator.finishWorkspaceSavePicker(uri?.let { DocumentExportTarget(context, it) })
    }
    val leave = {
        if (!coordinator.state.value.pickerActive) {
            coordinator.cancel()
            onBack()
        }
    }
    if (state.focusedPreview) {
        FocusedFilePreview(coordinator, state) {
            if (coordinator.closeFocusedPreview()) {
                if (!returnToPane) onBack()
                returnToPane = false
            }
        }
        return
    }
    BackHandler {
        if (state.pickerActive) return@BackHandler
        val parts = state.workspaceListing?.parts.orEmpty()
        if (pane == FilesPane.WORKSPACE && parts.isNotEmpty() && !state.busy) coordinator.browseWorkspace(parts.dropLast(1))
        else leave()
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TabRow(selectedTabIndex = currentPane.ordinal) {
            FilesPane.entries.forEach { item ->
                Tab(selected = currentPane == item, enabled = !state.pickerActive, onClick = {
                    pane = item
                    if (item == FilesPane.WORKSPACE && state.workspaceListing == null && !state.busy) coordinator.browseWorkspace()
                }, modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget),
                    text = { Text(stringResource(item.label)) })
            }
        }
        if (state.operation == FilesOperation.UPLOAD || state.uploadCancelled) {
            Column(Modifier.padding(horizontal = TokenWorksSpacing.lg, vertical = TokenWorksSpacing.sm)) {
                if (state.operation == FilesOperation.UPLOAD) FileTransferStatus(state, coordinator::cancel)
                if (state.uploadCancelled) TokenWorksStatusNotice(stringResource(R.string.files_audit_upload_cancelled), tone = TokenWorksNoticeTone.INFO)
            }
        }
        if (pane == FilesPane.EXPORTS && pickerError) Box(Modifier.padding(horizontal = TokenWorksSpacing.lg)) {
            TokenWorksStatusNotice(stringResource(R.string.workspace_files_picker_unavailable), tone = TokenWorksNoticeTone.ERROR)
        }
        when (currentPane) {
            FilesPane.ATTACHMENTS -> FileAttachmentsPanel(coordinator, sessionId, leave, requested, imageRequested,
                onPickerConsumed = {
                    attachmentPickerHandled = true
                    pane = FilesPane.ATTACHMENTS
                    onAttachmentPickerRequestConsumed()
                }, onImageConsumed = {
                    imagePickerHandled = true
                    pane = FilesPane.ATTACHMENTS
                    onImagePickerRequestConsumed()
                })
            FilesPane.WORKSPACE -> WorkspaceBrowser(state, coordinator::browseWorkspace, coordinator::addWorkspaceExport, { file ->
                if (coordinator.previewWorkspaceFile(file)) returnToPane = true
            }, coordinator::cancel, leave)
            FilesPane.EXPORTS -> ExportsScreen(state, { file ->
                pickerError = false
                if (coordinator.startWorkspaceSavePicker(file)) {
                    try { saveWorkspaceFile.launch(FilePreview.displayName(file.name)) }
                    catch (_: Exception) { coordinator.finishWorkspaceSavePicker(null); pickerError = true }
                }
            }, coordinator::removeWorkspaceExport, coordinator::cancel, leave)
            FilesPane.GALLERY -> SentImagesPane(sentImages, enabled = !state.busy && !state.pickerActive, onBack = leave) { index ->
                if (coordinator.openImageGallery(sessionId, sentImages, index)) returnToPane = true
            }
        }
    }
}

private enum class FilesPane(val label: Int) {
    ATTACHMENTS(R.string.workspace_files_attachments),
    WORKSPACE(R.string.workspace_files_browser),
    EXPORTS(R.string.workspace_files_exports),
    GALLERY(R.string.files_audit_gallery_tab),
}

@Composable
private fun FileAttachmentsPanel(coordinator: FilesCoordinator, sessionId: String, onBack: () -> Unit,
    launchPickerOnce: Boolean, launchImageOnce: Boolean, onPickerConsumed: () -> Unit, onImageConsumed: () -> Unit) {
    val state by coordinator.state.collectAsState()
    val context = LocalContext.current
    val actions = rememberCoroutineScope()
    var path by remember(coordinator) { mutableStateOf("") }
    var platformError by remember(coordinator) { mutableStateOf<Int?>(null) }
    val currentSession by rememberUpdatedState(sessionId)
    var imageSelectionSession by remember(coordinator) { mutableStateOf<String?>(null) }
    val selectImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        coordinator.pickerFinished()
        val selectedFor = imageSelectionSession
        imageSelectionSession = null
        if (uri != null && selectedFor != null && selectedFor == currentSession) actions.launch {
            try {
                val name = withContext(Dispatchers.IO) {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (!cursor.moveToFirst()) throw RemoteRequestException("files.document_unavailable")
                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0) cursor.getString(column) ?: "image" else "image"
                    } ?: throw RemoteRequestException("files.document_unavailable")
                }
                if (selectedFor == currentSession) coordinator.selectImage(selectedFor, name) {
                    context.contentResolver.openInputStream(uri) ?: throw RemoteRequestException("files.document_unavailable")
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { platformError = R.string.files_audit_image_read_error }
        }
    }
    val launchImage = {
        platformError = null
        if (coordinator.startAttachmentPicker()) {
            imageSelectionSession = sessionId
            try { selectImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            catch (_: Exception) {
                imageSelectionSession = null
                coordinator.pickerFinished()
                platformError = R.string.workspace_files_picker_unavailable
            }
        }
    }
    var cameraSession by remember(coordinator) { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        coordinator.pickerFinished()
        val selectedFor = cameraSession
        cameraSession = null
        if (bitmap != null && selectedFor != null && selectedFor == currentSession) actions.launch {
            try {
                val jpeg = withContext(Dispatchers.IO) {
                    require(bitmap.width.toLong() * bitmap.height in 1..4_000_000)
                    val output = ByteArrayOutputStream()
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
                    output.toByteArray()
                }
                if (selectedFor == currentSession) coordinator.selectImage(selectedFor, "photo.jpg", jpeg::inputStream)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { platformError = R.string.files_audit_image_read_error }
            finally { bitmap.recycle() }
        } else bitmap?.recycle()
    }
    val launchCamera = {
        if (coordinator.startAttachmentPicker()) {
            cameraSession = sessionId
            try { camera.launch(null) }
            catch (_: Exception) {
                cameraSession = null
                coordinator.pickerFinished()
                platformError = R.string.workspace_files_picker_unavailable
            }
        }
    }
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed && sessionId == currentSession) launchCamera() else platformError = R.string.files_camera_permission
    }
    LaunchedEffect(launchImageOnce) {
        if (launchImageOnce) { onImageConsumed(); launchImage() }
    }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) coordinator.pickerFinished()
        else actions.launch {
            try {
                val metadata = withContext(Dispatchers.IO) {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (!cursor.moveToFirst()) throw RemoteRequestException("files.document_unavailable")
                        val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                        (if (name >= 0) cursor.getString(name) ?: "attachment" else "attachment") to
                            (if (size >= 0 && !cursor.isNull(size)) cursor.getLong(size) else null)
                    } ?: throw RemoteRequestException("files.document_unavailable")
                }
                coordinator.pickerFinished()
                coordinator.upload(sessionId, metadata.first, metadata.second) {
                    context.contentResolver.openInputStream(uri) ?: throw RemoteRequestException("files.document_unavailable")
                }
            } catch (error: CancellationException) {
                coordinator.pickerFinished()
                throw error
            } catch (_: Exception) {
                coordinator.pickerFinished()
                platformError = R.string.files_audit_document_read_error
            }
        }
    }
    val launchUpload = {
        platformError = null
        if (coordinator.startAttachmentPicker()) {
            try { upload.launch(arrayOf("*/*")) }
            catch (_: Exception) { coordinator.pickerFinished(); platformError = R.string.workspace_files_picker_unavailable }
        }
    }
    LaunchedEffect(launchPickerOnce) {
        if (launchPickerOnce) {
            onPickerConsumed()
            launchUpload()
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        coordinator.finishPreviewSavePicker(uri?.let { DocumentExportTarget(context, it) })
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(TokenWorksSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
        Text(stringResource(R.string.workspace_files_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.files_audit_attachment_hint), style = MaterialTheme.typography.bodyMedium)
        if (state.busy && state.operation != FilesOperation.UPLOAD) FileTransferStatus(state, coordinator::cancel)
        (platformError?.let { stringResource(it) } ?: state.error?.let { workspaceFileError(it) })?.let {
            TokenWorksStatusNotice(it, tone = TokenWorksNoticeTone.ERROR)
        }
        if (state.exported) TokenWorksStatusNotice(stringResource(R.string.workspace_files_export_success), tone = TokenWorksNoticeTone.SUCCESS)
        Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.lg),
            color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(TokenWorksSpacing.md), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                Text(stringResource(R.string.files_audit_read_host_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                OutlinedTextField(path, { path = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.files_audit_path_label)) }, singleLine = true,
                    supportingText = { Text(stringResource(R.string.files_audit_path_hint)) })
                Button({ coordinator.load(path) }, enabled = !state.busy && !state.pickerActive && path.startsWith('/')) {
                    Text(stringResource(R.string.files_audit_read_file))
                }
                OutlinedButton(launchUpload, enabled = !state.busy && !state.pickerActive && state.uploaded.size < 16) {
                    Text(stringResource(R.string.files_audit_upload_attachment))
                }
            }
        }
        Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.lg),
            color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(TokenWorksSpacing.md), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                Text(stringResource(R.string.files_audit_vision_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                val imagesAllowed = !state.busy && !state.pickerActive && state.images.size < NativeImagePart.MAX_IMAGES
                OutlinedButton(launchImage, enabled = imagesAllowed) { Text(stringResource(R.string.files_audit_select_vision)) }
                OutlinedButton({
                    platformError = null
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
                    else requestCamera.launch(Manifest.permission.CAMERA)
                }, enabled = imagesAllowed) { Text(stringResource(R.string.files_camera_take_photo)) }
                Text(stringResource(R.string.files_audit_vision_limits),
                    style = MaterialTheme.typography.bodySmall, color = tokenWorksSurfaces().textTertiary)
            }
        }
        state.images.forEach { image ->
            val name = image.part.name ?: stringResource(R.string.files_audit_vision_title)
            val remove = stringResource(R.string.files_audit_remove_named, name)
            Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.md),
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xxs)) {
                        Text(stringResource(R.string.files_audit_image_pending, name, image.width, image.height), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.files_audit_image_copy), style = MaterialTheme.typography.bodySmall)
                        if (image.correctedOrientation) Text(stringResource(R.string.files_audit_image_corrected), style = MaterialTheme.typography.bodySmall)
                        if (image.reducedSize) Text(stringResource(R.string.files_audit_image_reduced, image.width, image.height), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(if (image.part.mediaType == "image/png") R.string.files_audit_image_alpha else R.string.files_audit_image_jpeg),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton({ coordinator.removeImage(image.part) }, enabled = !state.busy,
                        modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget).semantics { contentDescription = remove }) {
                        Text(stringResource(R.string.files_audit_remove))
                    }
                }
            }
        }
        state.uploaded.forEach { file ->
            val remove = stringResource(R.string.files_audit_remove_named, file.name)
            Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.md),
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                    Text(stringResource(R.string.files_audit_attachment_pending, file.name, fileSize(file.sizeBytes)), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton({ coordinator.removeUpload(file.receiptId) }, enabled = !state.busy,
                        modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget).semantics { contentDescription = remove }) {
                        Text(stringResource(R.string.files_audit_remove))
                    }
                }
            }
        }
        state.preview?.let { file ->
            Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.lg),
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(TokenWorksSpacing.md), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                    Text(file.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.files_audit_file_details, file.mediaType, fileSize(file.sizeBytes)), style = MaterialTheme.typography.labelMedium,
                        color = tokenWorksSurfaces().textTertiary)
                    FilePreviewScreen(file)
                    Button({
                        if (coordinator.startPreviewSavePicker(file)) {
                            try { export.launch(file.name) }
                            catch (_: Exception) { coordinator.finishPreviewSavePicker(null); platformError = R.string.workspace_files_picker_unavailable }
                        }
                    }, enabled = !state.busy && !state.pickerActive, modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                        Text(stringResource(R.string.files_audit_save_named, file.name))
                    }
                }
            }
        }
        TextButton(onBack, Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(stringResource(R.string.workspace_files_back)) }
    }
}

/** Android's document provider publishes on close; abort deletes the newly created destination. */
internal class DocumentExportTarget(context: Context, private val uri: Uri) : FileExportTarget {
    private val resolver = context.contentResolver
    private var opened = false
    private var committed = false
    override fun open(): OutputStream {
        check(!opened && !committed)
        opened = true
        return resolver.openOutputStream(uri, "wt") ?: throw RemoteRequestException("files.destination_unavailable")
    }
    override fun commit() { check(opened && !committed); committed = true }
    override fun abort() {
        if (!DocumentsContract.deleteDocument(resolver, uri)) throw RemoteRequestException("files.destination_cleanup_failed")
    }
}
