package cn.edu.ustc.tokenworks

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilesAuditTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun markdownPreviewIsLiteralExactBoundedAndNeverUsesAnExternalUri() = runBlocking {
        for (target in listOf("https://example.test/report.pdf", "file:///work/report.pdf", "content://host/report.pdf",
            "//host/report.pdf", "./report.pdf", "/work/../report.pdf", "/work/./report.pdf", "/work//report.pdf",
            "/work/%2e%2e/report.pdf", "/work/a%252Fb.pdf", "/work/report.pdf?url=host", "/work/report.pdf#page=2",
            "/work/", "/work\\report.pdf", "/work/\u202ereport.pdf", "/work/\ud800.pdf")) assertNull(target, FilesPreviewLink.parse(target))
        val accepted = FilesPreviewLink.parse("/work/报告 1.PDF")!!
        assertEquals("报告 1.PDF", accepted.name)
        assertTrue(accepted.isPdf)
        val route = Route()
        FilesCoordinator(route, directory.root, this, "selected-session").use { files ->
            assertFalse(files.openLinkedFile("other-session", accepted))
            assertEquals(emptyList<String>(), route.downloads)
            assertTrue(files.openLinkedFile("selected-session", accepted))
            val staged = files.finished().preview!!
            assertEquals("application/pdf", staged.mediaType)
            assertEquals(listOf("/api/file?path=%2Fwork%2F%E6%8A%A5%E5%91%8A%201.PDF"), route.downloads)
            files.detachTransport()
            assertFalse(staged.file.exists())
            assertFalse(files.openLinkedFile("selected-session", accepted))
            assertEquals(1, route.downloads.size)
        }
    }

    @Test fun workspacePreviewUsesOnlyTheSelectedListedFileAndThePinnedVersion() = runBlocking {
        val route = Route()
        FilesCoordinator(route, directory.root, this, "selected-session").use { files ->
            val forged = WorkspaceExportFile(listOf("report.txt"), "b".repeat(64), 3)
            assertFalse(files.previewWorkspaceFile(forged))
            files.browseWorkspace()
            val listed = files.finished().workspaceListing!!.entries.single() as WorkspaceExportFile
            assertFalse(files.previewWorkspaceFile(forged))
            assertTrue(files.previewWorkspaceFile(listed))
            val staged = files.finished().preview!!
            assertEquals("abc", staged.file.readText())
            assertEquals("text/plain", staged.mediaType)
            assertEquals(listOf("workspaceExports/list", "workspaceExports/read"), route.calls.map { it.first })
            val read = route.calls.last().second
            assertEquals("selected-session", read.string("sessionId"))
            val request = read.obj("request")
            assertEquals(listOf("report.txt".json()), request.array("parts"))
            assertEquals(listed.version, request.string("version"))
            assertTrue(route.downloads.isEmpty())
            files.closeFocusedPreview()
            assertFalse(staged.file.exists())
        }
    }

    @Test fun galleryOnlyReadsSelectedPagesAndRechecksTheSessionEveryTime() = runBlocking {
        val route = Route()
        val first = route.image("first.png")
        val second = route.image("second.png")
        val refs = listOf(first, second)
        FilesCoordinator(route, directory.root, this, "selected-session").use { files ->
            assertFalse(files.openImageGallery("other-session", refs, 0))
            assertFalse(files.openImageGallery("selected-session", refs, 2))
            assertTrue(files.openImageGallery("selected-session", refs, 1))
            val oldStage = files.finished().preview!!
            assertEquals(listOf("second.png"), route.requestedImages())
            files.selectGalleryImage(1)
            assertEquals(1, route.calls.size)
            files.selectGalleryImage(0)
            val current = files.finished().preview!!
            assertFalse(oldStage.file.exists())
            assertEquals(listOf("second.png", "first.png"), route.requestedImages())
            files.closeFocusedPreview()
            assertFalse(current.file.exists())
            assertNull(files.state.value.imageGallery)
        }
    }

    @Test fun sentGalleryExcludesDraftLiveAndAssistantImagesAndDeduplicates() {
        fun image(digit: Char) = NativeImageReference("sha256:${digit.toString().repeat(64)}", "image/png", 64, 4, 4)
        fun record(id: String, media: NativeImageReference, role: ConversationRole = ConversationRole.USER,
                   live: Boolean = false, saved: Boolean = true) = ConversationItem(id, role,
            listOf(DisplayBlock(BlockKind.IMAGE, "", media = media)), live, saved)
        val old = image('a')
        val newest = image('b')
        assertEquals(listOf(newest, old), sentImageGallery(listOf(record("1", old), record("2", old), record("3", newest),
            record("4", image('c'), saved = false), record("5", image('d'), live = true), record("6", image('e'), ConversationRole.ASSISTANT))))
    }

    @Test fun uploadUsesFreshRouteReportsKnownSizeAfterStagingAndCancellationAddsNoReceipt() = runBlocking {
        val old = Route()
        val fresh = Route()
        FilesCoordinator(old, directory.root, this, "selected-session").use { files ->
            files.detachTransport()
            files.upload("other-session", "draft.txt", null) { error("wrong session picked") }
            files.upload("selected-session", "draft.txt", null) { "abc".byteInputStream() }
            assertTrue(files.state.value.pickerActive)
            assertFalse(old.uploadStarted.isCompleted)
            files.attachTransport(fresh)
            withTimeout(3_000) { fresh.uploadStarted.await() }
            assertEquals(FileUploadPhase.SENDING, files.state.value.uploadPhase)
            assertEquals(3L, files.state.value.totalBytes)
            assertEquals(1L, files.state.value.progressBytes)
            files.cancel()
            assertTrue(files.state.value.uploadCancelled)
            assertTrue(files.state.value.uploaded.isEmpty())
            withTimeout(3_000) { fresh.uploadStopped.await() }
        }
    }

    private suspend fun FilesCoordinator.finished(): FilesUiState = withTimeout(3_000) { state.first { !it.busy } }

    private class Route : DshTransport {
        override val runtimeId = "runtime-a"
        override val state = MutableStateFlow(DshTransportState(DshTransportPath.LAN, DshTransportPhase.READY))
        val downloads = CopyOnWriteArrayList<String>()
        val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        val uploadStarted = CompletableDeferred<Unit>()
        val uploadStopped = CompletableDeferred<Unit>()
        private val imageData = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII="
        fun image(name: String): NativeImageReference {
            val bytes = Base64.getDecoder().decode(imageData)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return NativeImageReference("sha256:$hash", "image/png", bytes.size.toLong(), 1, 1, name)
        }
        fun requestedImages() = calls.map { it.second.obj("request").obj("attachment").string("name") }
        override suspend fun native(endpoint: String, args: JsonObject): JsonValue {
            calls.add(endpoint to args)
            return when (endpoint) {
                "workspaceExports/list" -> jsonObject("truncated" to JsonBoolean(false), "entries" to JsonArray(listOf(
                    jsonObject("kind" to "file".json(), "name" to "report.txt".json(), "size" to 3L.json(), "version" to "a".repeat(64).json())
                )))
                "workspaceExports/read" -> jsonObject("offset" to 0L.json(), "data" to "YWJj".json(), "eof" to JsonBoolean(true))
                "session/attachment" -> {
                    val request = args.obj("request")
                    assertEquals("selected-session", request.string("sessionId"))
                    jsonObject("attachment" to request.obj("attachment"), "data" to imageData.json())
                }
                else -> error("Unexpected native route")
            }
        }
        override suspend fun openMux(): DshMux = error("No mux expected")
        override suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?): DshDownload {
            downloads.add(path)
            assertEquals(FilePreview.MAX_PREVIEW_BYTES.toLong(), maxBytes)
            assertNull(range)
            val bytes = "%PDF-1.4".toByteArray()
            sink.write(bytes)
            return DshDownload("application/octet-stream", bytes.size.toLong(), null, null)
        }
        override suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long): JsonValue {
            assertEquals("/api/session/uploadFileBinary?sessionId=selected-session&name=draft.txt", path)
            assertEquals(3L, sizeBytes)
            assertEquals('a'.code, source.read())
            uploadStarted.complete(Unit)
            try { awaitCancellation() } finally { uploadStopped.complete(Unit) }
        }
        override fun close() = Unit
    }
}
