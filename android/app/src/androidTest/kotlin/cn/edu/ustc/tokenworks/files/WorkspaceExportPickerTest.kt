package cn.edu.ustc.tokenworks

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceExportPickerTest {
    @Test fun resultBeforeForegroundWaitsForFreshRouteAndRetirementDeletesAnAbandonedTarget() = runBlocking {
        val privateDirectory = Files.createTempDirectory("workspace-export-picker").toFile()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val retiredRoute = WorkspaceTransport(readable = false)
        val coordinator = FilesCoordinator(retiredRoute, privateDirectory, owner, "session-test")
        try {
            coordinator.browseWorkspace()
            val file = withTimeout(3_000) { coordinator.state.first { it.workspaceListing != null } }
                .workspaceListing!!.entries.single() as WorkspaceExportFile
            coordinator.addWorkspaceExport(file)
            assertTrue(coordinator.startWorkspaceSavePicker(file))
            coordinator.detachTransport()
            val destination = Destination()
            coordinator.finishWorkspaceSavePicker(destination)
            assertTrue(coordinator.state.value.pickerActive)
            assertFalse(destination.committed.isCompleted)

            val freshRoute = WorkspaceTransport(readable = true)
            coordinator.attachTransport(freshRoute)
            withTimeout(3_000) { coordinator.state.first { it.exported } }
            assertFalse(coordinator.state.value.pickerActive)
            assertTrue(coordinator.state.value.workspaceExports.single().saved)
            assertEquals(42, destination.bytes.toByteArray().single().toInt())
            assertEquals(0, retiredRoute.reads)
            assertEquals(1, freshRoute.reads)

            assertTrue(coordinator.startWorkspaceSavePicker(file))
            coordinator.detachTransport()
            val abandoned = Destination()
            coordinator.finishWorkspaceSavePicker(abandoned)
            coordinator.close()
            withTimeout(3_000) { abandoned.aborted.await() }
            assertFalse(abandoned.committed.isCompleted)
        } finally {
            coordinator.close()
            owner.cancel()
            privateDirectory.deleteRecursively()
        }
        Unit
    }

    private class WorkspaceTransport(private val readable: Boolean) : DshTransport {
        override val runtimeId = "workspace-runtime"
        override val state = MutableStateFlow(DshTransportState(DshTransportPath.LAN, DshTransportPhase.READY))
        var reads = 0
        override suspend fun native(endpoint: String, args: JsonObject): JsonValue {
            assertEquals("session-test", args.string("sessionId"))
            return when (endpoint) {
                "workspaceExports/list" -> jsonObject("truncated" to JsonBoolean(false), "entries" to JsonArray(listOf(
                    jsonObject("kind" to "file".json(), "name" to "result.bin".json(), "size" to 1L.json(), "version" to "a".repeat(64).json()),
                )))
                "workspaceExports/read" -> {
                    check(readable)
                    reads++
                    jsonObject("offset" to 0L.json(), "data" to "Kg==".json(), "eof" to JsonBoolean(true))
                }
                else -> error("unexpected file endpoint")
            }
        }
        override suspend fun openMux(): DshMux = error("unused")
        override suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?): DshDownload = error("unused")
        override suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long): JsonValue = error("unused")
        override fun close() {}
    }

    private class Destination : FileExportTarget {
        val bytes = ByteArrayOutputStream()
        val committed = CompletableDeferred<Unit>()
        val aborted = CompletableDeferred<Unit>()
        override fun open(): OutputStream = bytes
        override fun commit() { committed.complete(Unit) }
        override fun abort() { bytes.reset(); aborted.complete(Unit) }
    }
}
