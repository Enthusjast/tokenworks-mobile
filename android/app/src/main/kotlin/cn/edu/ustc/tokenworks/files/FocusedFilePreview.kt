package cn.edu.ustc.tokenworks

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@Composable
internal fun SentImagesPane(images: List<NativeImageReference>, enabled: Boolean, onBack: () -> Unit, openImage: (Int) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(TokenWorksSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
        item {
            Text(stringResource(R.string.files_audit_sent_images), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.files_audit_gallery_hint), style = MaterialTheme.typography.bodyMedium)
        }
        if (images.isEmpty()) item { Text(stringResource(R.string.files_audit_gallery_empty)) }
        itemsIndexed(images, key = { _, image -> image.attachmentId }) { index, image ->
            OutlinedButton({ openImage(index) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                Column(Modifier.fillMaxWidth()) {
                    Text(image.name ?: stringResource(R.string.files_audit_image_number, index + 1))
                    Text(stringResource(R.string.files_audit_image_dimensions, image.width, image.height),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { TextButton(onBack, Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(stringResource(R.string.workspace_files_back)) } }
    }
}

@Composable
internal fun FocusedFilePreview(coordinator: FilesCoordinator, state: FilesUiState, onBack: () -> Unit) {
    val context = LocalContext.current
    var pickerUnavailable by remember(coordinator, state.preview) { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        coordinator.finishPreviewSavePicker(uri?.let { DocumentExportTarget(context, it) })
    }
    val canClose = !state.pickerActive && state.operation != FilesOperation.EXPORT
    BackHandler { if (canClose) onBack() }
    val gallery = state.imageGallery
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = TokenWorksSpacing.lg)) {
        TextButton(onBack, enabled = canClose, modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
            Text(stringResource(R.string.files_audit_close_preview))
        }
        val actions: @Composable () -> Unit = {
            if (pickerUnavailable) TokenWorksStatusNotice(stringResource(R.string.workspace_files_picker_unavailable), tone = TokenWorksNoticeTone.ERROR)
            if (state.exported) TokenWorksStatusNotice(stringResource(R.string.workspace_files_export_success), tone = TokenWorksNoticeTone.SUCCESS)
            if (state.exportCancelled) TokenWorksStatusNotice(stringResource(R.string.workspace_files_export_cancelled), tone = TokenWorksNoticeTone.INFO)
            if (state.busy) FileTransferStatus(state, coordinator::cancel)
            state.error?.let { TokenWorksStatusNotice(workspaceFileError(it), tone = TokenWorksNoticeTone.ERROR) }
            state.preview?.let { file ->
                Text(stringResource(R.string.workspace_files_size, Formatter.formatFileSize(context, file.sizeBytes)), style = MaterialTheme.typography.bodySmall)
                OutlinedButton({
                    if (coordinator.startPreviewSavePicker(file)) {
                        pickerUnavailable = false
                        try { save.launch(file.name) }
                        catch (_: Exception) { coordinator.finishPreviewSavePicker(null); pickerUnavailable = true }
                    }
                }, enabled = !state.busy && !state.pickerActive, modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                    Text(stringResource(R.string.files_audit_save_named, file.name))
                }
                FilePreviewScreen(file)
            }
        }
        if (gallery == null) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                Text(state.path, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                actions()
            }
        } else {
            key(gallery.references) {
                val pager = rememberPagerState(initialPage = gallery.selectedIndex) { gallery.references.size }
                val scope = rememberCoroutineScope()
                val navigable = canClose && gallery.references.size > 1
                LaunchedEffect(pager, coordinator) {
                    // Ignore pager restoration/composition; load only after a user swipe or gallery button.
                    pager.scrollToPage(gallery.selectedIndex)
                    snapshotFlow { pager.settledPage }.drop(1).collect { coordinator.selectGalleryImage(it) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ scope.launch { pager.scrollToPage(gallery.selectedIndex - 1) } }, enabled = navigable && gallery.selectedIndex > 0,
                        modifier = Modifier.weight(1f).heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                        Text(stringResource(R.string.files_audit_previous_image))
                    }
                    TextButton({ scope.launch { pager.scrollToPage(gallery.selectedIndex + 1) } }, enabled = navigable && gallery.selectedIndex < gallery.references.lastIndex,
                        modifier = Modifier.weight(1f).heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                        Text(stringResource(R.string.files_audit_next_image))
                    }
                }
                Text(stringResource(R.string.files_audit_gallery_position, gallery.selectedIndex + 1, gallery.references.size),
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                HorizontalPager(pager, modifier = Modifier.fillMaxWidth().weight(1f), beyondViewportPageCount = 0,
                    userScrollEnabled = navigable, verticalAlignment = Alignment.Top) { index ->
                    val image = gallery.references[index]
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = TokenWorksSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                        Text(image.name ?: stringResource(R.string.files_audit_image_number, index + 1), style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() })
                        if (index == gallery.selectedIndex) {
                            actions()
                            if (state.preview == null && !state.busy) TextButton(coordinator::retryGalleryImage, enabled = canClose,
                                modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(stringResource(R.string.files_audit_fetch_image)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FileTransferStatus(state: FilesUiState, cancel: () -> Unit) {
    val label = when (state.operation) {
        FilesOperation.UPLOAD -> if (state.uploadPhase == FileUploadPhase.PREPARING) R.string.files_audit_preparing_upload else R.string.files_audit_sending_upload
        FilesOperation.PREPARE_IMAGE -> R.string.files_audit_preparing_image
        FilesOperation.EXPORT -> R.string.files_audit_saving
        FilesOperation.BROWSE -> R.string.workspace_files_loading
        else -> R.string.files_audit_loading
    }
    Text(stringResource(label), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
    val total = state.totalBytes?.takeIf { it >= 0 }
    val count = state.progressBytes
    val context = LocalContext.current
    if (total != null) {
        val fraction = if (total == 0L) 1f else (count.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.files_audit_transfer_total, Formatter.formatFileSize(context, count), Formatter.formatFileSize(context, total)),
            style = MaterialTheme.typography.bodySmall)
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        if (count > 0L) Text(Formatter.formatFileSize(context, count), style = MaterialTheme.typography.bodySmall)
    }
    TextButton(cancel, Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
        Text(stringResource(if (state.operation == FilesOperation.UPLOAD) R.string.files_audit_cancel_upload else R.string.files_audit_cancel_transfer))
    }
}
