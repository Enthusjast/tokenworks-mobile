package cn.edu.ustc.tokenworks

import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedFileOutboxStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val route = DshCommandRoute("account-A", "host-A", "runtime-A", "session-A")
    private val file: File get() = File(directory.root, "commands.bin")

    @Test fun changingAnyRouteFieldWithAnAuthenticManifestStillFailsEntryAuthentication() = runBlocking {
        val store = EncryptedFileOutboxStore(file, OutboxKeyProvider { key })
        DshCommandOutbox.open(store).prepare(route, "private intent", images = listOf(nativeOutboxImage()))
        val original = file.readBytes()
        repeat(4) { changedField ->
            val clear = decryptManifest(original, key)
            val cursor = ByteBuffer.wrap(clear).apply { position(20) } // v2, revision, count, first route byte length
            repeat(changedField) { val length = cursor.int; cursor.position(cursor.position() + length) }
            val length = cursor.int
            clear[cursor.position() + length - 1] = 'B'.code.toByte()
            val modified = encryptManifest(clear, key)
            clear.fill(0)
            file.writeBytes(modified)
            try { store.load(); fail("route field $changedField was not authenticated") }
            catch (error: OutboxException) { assertEquals(OutboxFailure.CORRUPT, error.failure) }
            assertArrayEquals(modified, file.readBytes())
        }
        file.writeBytes(original)
        assertEquals(route, store.load().entries.single().route)
    }

    @Test fun keyAndDiskOperationsRunOffTheCallingThread() = runBlocking {
        val caller = Thread.currentThread()
        val threads = mutableListOf<Thread>()
        val store = EncryptedFileOutboxStore(file, OutboxKeyProvider { threads.add(Thread.currentThread()); key })
        val outbox = DshCommandOutbox.open(store)
        val entry = outbox.prepare(route, "hello")
        outbox.markSending(entry.requestId, route)
        outbox.settle(entry.requestId, route)
        assertTrue(threads.isNotEmpty())
        assertTrue(threads.all { it !== caller })
        assertTrue(DshCommandOutbox.open(store).snapshot.value.isEmpty())
    }

    @Test fun authenticLegacyV1CheckpointIsRejectedWithoutFallbackOrOverwrite() {
        val legacyRoute = binary { it.text(route.ownerId); it.text(route.hostId); it.text(route.runtimeId); it.text(route.sessionId) }
        val legacyPayload = binary {
            it.text("00000000-0000-4000-8000-000000000002"); it.text("legacy private text"); it.text("queue")
            it.writeInt(PromptDeliveryState.PENDING.ordinal); it.writeInt(0)
        }
        val legacyEntry = encrypt(legacyPayload, "tokenworks-mobile/dsh-command-entry/v1".toByteArray() + legacyRoute)
        val legacy = binary {
            it.writeLong(1); it.writeInt(1); it.writeInt(legacyRoute.size); it.write(legacyRoute)
            it.writeInt(legacyEntry.size); it.write(legacyEntry)
        }
        val encrypted = encryptManifest(legacy, key, "tokenworks-mobile/dsh-command-outbox/v1")
        file.writeBytes(encrypted)
        assertCorruptUnchanged(encrypted)
    }

    @Test fun unsupportedAuthenticatedManifestVersionsFailClosed() = runBlocking {
        DshCommandOutbox.open(EncryptedFileOutboxStore(file, OutboxKeyProvider { key }))
            .prepare(route, "", images = listOf(nativeOutboxImage()))
        val original = file.readBytes()
        for (version in listOf(0, 1, 4, Int.MAX_VALUE)) {
            val clear = decryptManifest(original, key)
            ByteBuffer.wrap(clear).putInt(version)
            val encrypted = encryptManifest(clear, key)
            clear.fill(0)
            file.writeBytes(encrypted)
            assertCorruptUnchanged(encrypted)
        }
    }

    @Test fun versionTwoPendingEntryIsReadableButNotProvenUnsent() = runBlocking {
        val routeBytes = binary { it.text(route.ownerId); it.text(route.hostId); it.text(route.runtimeId); it.text(route.sessionId) }
        val id = "00000000-0000-4000-8000-000000000002"
        val oldPayload = binary {
            it.text(id); it.text("preserved"); it.text("queue")
            it.writeInt(PromptDeliveryState.PENDING.ordinal); it.writeInt(0); it.writeInt(0)
        }
        val oldEntry = encrypt(oldPayload, "tokenworks-mobile/dsh-command-entry/v2".toByteArray() + routeBytes)
        val manifest = binary {
            it.writeInt(2); it.writeLong(1); it.writeInt(1)
            it.writeInt(routeBytes.size); it.write(routeBytes); it.writeInt(oldEntry.size); it.write(oldEntry)
        }
        file.writeBytes(encryptManifest(manifest, key))
        val outbox = DshCommandOutbox.open(EncryptedFileOutboxStore(file, OutboxKeyProvider { key }))
        assertEquals("preserved", outbox.pending(route).single().text)
        assertFalse(outbox.pending(route).single().canEdit)
        assertNull(outbox.editPendingText(id, route, "rewrite"))
        assertEquals(id, outbox.retry(id, route)!!.requestId)
    }

    @Test fun entryCiphertextRemainsAuthenticatedInsideAnAuthenticManifest() = runBlocking {
        DshCommandOutbox.open(EncryptedFileOutboxStore(file, OutboxKeyProvider { key }))
            .prepare(route, "", images = listOf(nativeOutboxImage()))
        val clear = decryptManifest(file.readBytes(), key)
        clear[clear.lastIndex] = (clear.last().toInt() xor 1).toByte()
        val encrypted = encryptManifest(clear, key)
        clear.fill(0)
        file.writeBytes(encrypted)
        assertCorruptUnchanged(encrypted)
    }

    @Test fun fileManifestAndAuthenticatedImagePayloadTruncationAreNeverEmptySuccess() = runBlocking {
        DshCommandOutbox.open(EncryptedFileOutboxStore(file, OutboxKeyProvider { key }))
            .prepare(route, "private text", receipts = listOf("opaque receipt"), images = listOf(nativeOutboxImage()))
        val original = file.readBytes()
        for (length in listOf(0, 1, 11, 12, 27, 28, original.size / 2, original.size - 1)) {
            val truncated = original.copyOf(length)
            file.writeBytes(truncated)
            assertCorruptUnchanged(truncated)
        }
        val manifest = decryptManifest(original, key)
        for (length in listOf(0, 3, 4, 11, 12, 15, manifest.size - 1)) {
            val truncated = encryptManifest(manifest.copyOf(length), key)
            file.writeBytes(truncated)
            assertCorruptUnchanged(truncated)
        }
        val payload = entryPayload(manifest)
        for (length in listOf(0, 4, 35, payload.size / 2, payload.size - 5, payload.size - 1)) {
            val truncated = encryptManifest(withEntryPayload(manifest, payload.copyOf(length)), key)
            file.writeBytes(truncated)
            assertCorruptUnchanged(truncated)
        }
        val trailing = encryptManifest(withEntryPayload(manifest, payload + byteArrayOf(0)), key)
        file.writeBytes(trailing)
        assertCorruptUnchanged(trailing)
        file.writeBytes(original)
        assertEquals(nativeOutboxImage(), EncryptedFileOutboxStore(file, OutboxKeyProvider { key }).load().entries.single().images.single())
    }

    @Test fun oversizedFilesAreRejectedBeforeTheyCanBeReadAsSnapshots() {
        RandomAccessFile(file, "rw").use { it.setLength(EncryptedFileOutboxStore.MAX_FILE_BYTES.toLong() + 1) }
        val store = EncryptedFileOutboxStore(file, OutboxKeyProvider { key })
        val error = assertThrows(OutboxException::class.java) { store.load() }
        assertEquals(OutboxFailure.CORRUPT, error.failure)
        assertEquals(EncryptedFileOutboxStore.MAX_FILE_BYTES.toLong() + 1, file.length())
    }

    private fun assertCorruptUnchanged(encrypted: ByteArray) {
        val error = assertThrows(OutboxException::class.java) {
            EncryptedFileOutboxStore(file, OutboxKeyProvider { key }).load()
        }
        assertEquals(OutboxFailure.CORRUPT, error.failure)
        assertArrayEquals(encrypted, file.readBytes())
    }

    private fun entryPayload(manifest: ByteArray): ByteArray = DataInputStream(ByteArrayInputStream(manifest)).use {
        it.readInt(); it.readLong(); assertEquals(1, it.readInt())
        val routeBytes = ByteArray(it.readInt()).also(it::readFully)
        val entry = ByteArray(it.readInt()).also(it::readFully)
        decrypt(entry, "tokenworks-mobile/dsh-command-entry/v2".toByteArray() + routeBytes)
    }

    private fun withEntryPayload(manifest: ByteArray, payload: ByteArray): ByteArray {
        val cursor = ByteBuffer.wrap(manifest).apply { position(16) }
        val routeBytes = ByteArray(cursor.int).also { cursor.get(it) }
        val prefixBytes = cursor.position()
        val encrypted = encrypt(payload, "tokenworks-mobile/dsh-command-entry/v2".toByteArray() + routeBytes)
        return binary { it.write(manifest, 0, prefixBytes); it.writeInt(encrypted.size); it.write(encrypted) }
    }

    private fun binary(action: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use(action)
        bytes.toByteArray()
    }
    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8); writeInt(bytes.size); write(bytes)
    }
    private fun encrypt(bytes: ByteArray, aad: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key); updateAAD(aad); iv + doFinal(bytes)
    }
    private fun decrypt(bytes: ByteArray, aad: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12)); updateAAD(aad)
        doFinal(bytes, 12, bytes.size - 12)
    }

    private fun decryptManifest(bytes: ByteArray, key: SecretKey): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
        updateAAD("tokenworks-mobile/dsh-command-outbox/v2".toByteArray())
        doFinal(bytes, 12, bytes.size - 12)
    }

    private fun encryptManifest(bytes: ByteArray, key: SecretKey, domain: String = "tokenworks-mobile/dsh-command-outbox/v2"): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key)
        updateAAD(domain.toByteArray())
        iv + doFinal(bytes)
    }
}
