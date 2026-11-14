package cn.edu.ustc.tokenworks

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeImageSelectionTest {
    private fun fixture(): ByteArray {
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            return ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }
    private class LocalOnlyTransport : DshTransport {
        override val runtimeId = "local-image-selection"
        override val state = MutableStateFlow(DshTransportState(DshTransportPath.LAN, DshTransportPhase.READY))
        override suspend fun native(endpoint: String, args: JsonObject): JsonValue = error("image selection must not send native requests")
        override suspend fun openMux(): DshMux = error("image selection must not open a stream")
        override suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?): DshDownload = error("image selection must not download")
        override suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long): JsonValue = error("vision images must not use generic file uploads")
        override fun close() {}
    }

    @Test fun selectedPixelsStayLocalAndSessionBoundAndCommitConsumesOnlyCapturedParts() = runBlocking {
        val directory = Files.createTempDirectory("selected-native-image").toFile()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coordinator = FilesCoordinator(LocalOnlyTransport(), directory, owner, "session-a")
        val original = fixture()
        val before = original.clone()
        try {
            coordinator.selectImage("session-a", "first.png") { original.inputStream() }
            val first = withTimeout(10_000) { coordinator.state.first { !it.busy && it.images.size == 1 }.images.single() }
            val captured = coordinator.imagesFor("session-a")
            assertEquals(listOf(first.part), captured)
            assertTrue(coordinator.imagesFor("session-b").isEmpty())
            val decoded = Base64.decode(first.part.data, Base64.NO_WRAP)
            val pixels = BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
            try { assertEquals(Color.RED, pixels.getPixel(3, 3)); assertEquals(12, pixels.width); assertEquals(8, pixels.height) }
            finally { pixels.recycle() }
            assertArrayEquals(before, original)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            coordinator.selectImage("session-b", "wrong.png") { error("wrong-session selection must not read source") }
            assertEquals(1, coordinator.state.value.images.size)
            assertEquals("files.image_session_changed", coordinator.state.value.error)
            coordinator.selectImage("session-a", "second.png") { original.inputStream() }
            val second = withTimeout(10_000) { coordinator.state.first { !it.busy && it.images.size == 2 }.images.last() }
            coordinator.consumeImages(captured)
            assertEquals(listOf(second.part), coordinator.imagesFor("session-a"))
            coordinator.removeImage(second.part)
            assertTrue(coordinator.imagesFor("session-a").isEmpty())
        } finally { coordinator.close(); owner.cancel(); directory.deleteRecursively() }
    }

    @Test fun cancelledSelectionClosesItsSourceAndCannotResurrectAnImage() = runBlocking {
        val directory = Files.createTempDirectory("cancel-native-image").toFile()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coordinator = FilesCoordinator(LocalOnlyTransport(), directory, owner, "session-a")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val original = fixture()
        try {
            coordinator.selectImage("session-a", "cancel.png") {
                started.complete(Unit)
                runBlocking { release.await() }
                object : ByteArrayInputStream(original) { override fun close() { super.close(); closed.complete(Unit) } }
            }
            withTimeout(10_000) { started.await() }
            coordinator.cancel()
            release.complete(Unit)
            withTimeout(10_000) { closed.await() }
            assertFalse(coordinator.state.value.busy)
            assertTrue(coordinator.state.value.images.isEmpty())
            coordinator.close()
            coordinator.selectImage("session-a", "closed.png") { error("closed owner must not read source") }
            assertTrue(coordinator.state.value.images.isEmpty())
        } finally { release.complete(Unit); coordinator.close(); owner.cancel(); directory.deleteRecursively() }
    }
}
