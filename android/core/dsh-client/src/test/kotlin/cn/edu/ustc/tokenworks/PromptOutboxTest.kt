package cn.edu.ustc.tokenworks

import java.io.File
import java.io.IOException
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PromptOutboxTest {
    @get:Rule val directory = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val route = DshCommandRoute("account|organization|deployment", "host-1", "runtime-1", "session-1")
    private val file: File get() = File(directory.root, "commands.bin")
    private fun store() = EncryptedFileOutboxStore(file, OutboxKeyProvider { key })
    private suspend fun open() = DshCommandOutbox.open(store())

    @Test fun persistedNewIntentsSurviveRestartWithoutTextDeduplication() = runBlocking {
        val outbox = open()
        val first = outbox.forRoute(route).prepare("hello", "queue")
        val second = outbox.forRoute(route).prepare("hello", "queue")
        assertNotEquals(first.requestId, second.requestId)
        assertEquals(listOf(first, second), open().pending(route))
        assertEquals(listOf(first, second), outbox.snapshot.value)
    }

    @Test fun crashDuringSendRecoversUnknownAndExplicitRetryKeepsId() = runBlocking {
        val outbox = open()
        val first = outbox.prepare(route, "hello")
        outbox.markSending(first.requestId, route)
        val restarted = open()
        val unknown = restarted.pending(route).single()
        assertEquals(first.requestId, unknown.requestId)
        assertEquals(PromptDeliveryState.OUTCOME_UNKNOWN, unknown.state)
        assertEquals(PromptDeliveryState.OUTCOME_UNKNOWN, open().pending(route).single().state)
        assertThrows(IllegalStateException::class.java) { runBlocking { restarted.markSending(first.requestId, route) } }
        val retry = restarted.retry(first.requestId, route)!!
        assertEquals(first.requestId, retry.requestId)
        assertEquals(PromptDeliveryState.PENDING, retry.state)
        restarted.markSending(retry.requestId, route)
        restarted.markUnknown(retry.requestId, route)
        assertEquals(first.requestId, open().pending(route).single().requestId)
    }

    @Test fun settleAndAbandonAreDurable() = runBlocking {
        val outbox = open()
        val settled = outbox.prepare(route, "one")
        val abandoned = outbox.prepare(route, "two", "steer")
        assertNotNull(outbox.settle(settled.requestId, route))
        assertNull(outbox.retry(settled.requestId, route))
        assertNotNull(outbox.abandon(abandoned.requestId))
        assertNull(outbox.settle(settled.requestId, route))
        assertTrue(open().pending(route).isEmpty())
    }

    @Test fun refusedEntryCannotSendWithoutExplicitRetry() = runBlocking {
        val outbox = open()
        val entry = outbox.prepare(route, "one")
        outbox.markSending(entry.requestId, route)
        outbox.markRefused(entry.requestId, route)
        val restarted = open()
        assertEquals(PromptDeliveryState.REFUSED, restarted.pending(route).single().state)
        assertThrows(IllegalStateException::class.java) { runBlocking { restarted.markSending(entry.requestId, route) } }
        assertEquals(entry.requestId, restarted.retry(entry.requestId, route)!!.requestId)
    }

    @Test fun everyIdentityFieldScopesLookupAndMutation() = runBlocking {
        val outbox = open()
        val original = outbox.prepare(route, "same text", images = listOf(nativeOutboxImage()))
        val otherRoutes = listOf(
            route.copy(ownerId = "another-owner"), route.copy(hostId = "another-host"),
            route.copy(runtimeId = "another-runtime"), route.copy(sessionId = "another-session"),
        )
        otherRoutes.forEach { other ->
            assertTrue(outbox.pending(other).isEmpty())
            assertNull(outbox.retry(original.requestId, other))
            assertNull(outbox.markSending(original.requestId, other))
            assertNull(outbox.settle(original.requestId, other))
            val own = outbox.prepare(other, "same text")
            assertEquals(listOf(own), outbox.pending(other))
        }
        assertEquals(listOf(original), open().pending(route))
    }

    @Test fun revocationClearsOnlyMatchingOwnerHostAndRuntime() = runBlocking {
        val outbox = open()
        val sameRuntimeDifferentSession = route.copy(sessionId = "session-2")
        val otherRuntime = route.copy(runtimeId = "runtime-2")
        val otherHost = route.copy(hostId = "host-2")
        val otherOwner = route.copy(ownerId = "owner-2")
        listOf(route, sameRuntimeDifferentSession, otherRuntime, otherHost, otherOwner).forEach { outbox.prepare(it, "hello") }
        assertEquals(2, outbox.revokeRuntime(route.ownerId, route.hostId, route.runtimeId).size)
        assertTrue(open().pending(route).isEmpty())
        assertTrue(open().pending(sameRuntimeDifferentSession).isEmpty())
        assertEquals(1, open().pending(otherRuntime).size)
        assertEquals(1, outbox.clearHost(route.ownerId, route.hostId).size)
        assertEquals(1, outbox.clearOwner(route.ownerId).size)
        assertEquals(listOf(otherOwner), open().snapshot.value.map { it.route })
    }

    @Test fun diskContainsNoPromptRoutingIdentityOrKeyMaterial() = runBlocking {
        val outbox = open()
        val text = "private prompt with Bearer very-secret-token and 中文"
        val image = nativeOutboxImage(name = "private-image-name.png")
        outbox.prepare(route, text, images = listOf(image))
        directory.root.walkTopDown().filter { it.isFile }.forEach { disk ->
            val bytes = disk.readBytes()
            listOf(text.toByteArray(), "very-secret-token".toByteArray(), route.ownerId.toByteArray(), route.sessionId.toByteArray(),
                image.data.toByteArray(), image.name!!.toByteArray(), java.util.Base64.getDecoder().decode(image.data), key.encoded).forEach { secret ->
                assertFalse("plaintext in ${disk.name}", bytes.containsBytes(secret))
            }
        }
    }

    @Test fun ciphertextTamperingFailsClosedWithoutOverwritingUnknownEntries() = runBlocking {
        val outbox = open()
        val entry = outbox.prepare(route, "valuable unknown intent", images = listOf(nativeOutboxImage()))
        outbox.markSending(entry.requestId, route)
        val tampered = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(tampered)
        expectFailure(OutboxFailure.CORRUPT) { open() }
        expectFailure(OutboxFailure.CORRUPT) { outbox.prepare(route, "must not overwrite") }
        assertArrayEquals(tampered, file.readBytes())
        assertEquals(PromptDeliveryState.SENDING, outbox.snapshot.value.single().state)
    }

    @Test fun wrongKeyAndTruncationAreNeverTreatedAsAnEmptyOutbox() = runBlocking {
        open().prepare(route, "retained", images = listOf(nativeOutboxImage()))
        val bytes = file.readBytes()
        val wrongKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        expectFailure(OutboxFailure.CORRUPT) { DshCommandOutbox.open(EncryptedFileOutboxStore(file, OutboxKeyProvider { wrongKey })) }
        assertArrayEquals(bytes, file.readBytes())
        file.writeBytes(bytes.copyOf(20))
        expectFailure(OutboxFailure.CORRUPT) { open() }
        assertEquals(20L, file.length())
    }

    @Test fun atomicReplacementFaultRetainsPreviousDurableStateAndSnapshot() = runBlocking {
        val previous = open().prepare(route, "already durable", images = listOf(nativeOutboxImage()))
        val bytes = file.readBytes()
        val faultyStore = EncryptedFileOutboxStore(file, OutboxKeyProvider { key }) { throw IOException("injected pre-rename failure") }
        val faulty = DshCommandOutbox.open(faultyStore)
        expectFailure(OutboxFailure.UNAVAILABLE) { faulty.prepare(route, "must not publish", images = listOf(nativeOutboxImage(name = "new.png"))) }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(listOf(previous), faulty.snapshot.value)
        assertEquals(listOf(previous), open().pending(route))
        assertTrue(directory.root.listFiles()!!.none { it.name.endsWith(".tmp") })
        expectFailure(OutboxFailure.UNAVAILABLE) { faulty.prepare(route, "requires reopen") }
    }

    @Test fun staleWriterCannotOverwriteAnotherOpenHandle() = runBlocking {
        val first = open()
        val stale = open()
        val retained = first.prepare(route, "retained")
        expectFailure(OutboxFailure.CONFLICT) { stale.prepare(route, "stale") }
        assertEquals(listOf(retained), open().pending(route))
    }

    @Test fun boundsCountUtf8BytesAndRejectInvalidUnicodeAndModes(): Unit = runBlocking {
        val outbox = open()
        val exactly = "😀".repeat(DshCommandOutbox.MAX_TEXT_BYTES / 4)
        val entry = outbox.prepare(route, exactly)
        assertEquals(entry, open().pending(route).single())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { outbox.prepare(route, exactly + "x") } }
        assertThrows(ProtocolException::class.java) { runBlocking { outbox.prepare(route, "\uD800") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { outbox.prepare(route, "x", "interrupt") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { outbox.prepare(route, " ") } }
    }

    @Test fun aggregateBytesAreBoundedWithoutEvictingExistingIntents() = runBlocking {
        val outbox = open()
        repeat(DshCommandOutbox.MAX_TOTAL_TEXT_BYTES / DshCommandOutbox.MAX_TEXT_BYTES) { outbox.prepare(route, "中".repeat(DshCommandOutbox.MAX_TEXT_BYTES / 3) + "ab") }
        val previous = file.readBytes()
        expectFailure(OutboxFailure.FULL) { outbox.prepare(route, "x") }
        assertArrayEquals(previous, file.readBytes())
        assertEquals(8, open().pending(route).size)
    }

    @Test fun entryCountIsBoundedWithoutEvictingExistingIntents() = runBlocking {
        val outbox = open()
        repeat(DshCommandOutbox.MAX_ENTRIES) { outbox.prepare(route, "x") }
        expectFailure(OutboxFailure.FULL) { outbox.prepare(route, "x") }
        assertEquals(DshCommandOutbox.MAX_ENTRIES, open().pending(route).size)
    }

    @Test fun existingCiphertextRequiresAnExistingKey() = runBlocking {
        open().prepare(route, "retained")
        val previous = file.readBytes()
        var createRequested = false
        val missingKey = OutboxKeyProvider { create ->
            createRequested = createRequested || create
            throw OutboxException(OutboxFailure.UNAVAILABLE)
        }
        expectFailure(OutboxFailure.UNAVAILABLE) { DshCommandOutbox.open(EncryptedFileOutboxStore(file, missingKey)) }
        assertFalse(createRequested)
        assertArrayEquals(previous, file.readBytes())
    }

    @Test fun imageOnlyIntentIsDurableBeforeDeliveryAndRetryPreservesEveryByteAndIdentity() = runBlocking {
        val outbox = open()
        val images = mutableListOf(nativeOutboxImage(name = "原样.png"), nativeOutboxImage(name = null))
        val originalImages = images.toList()
        val prepared = outbox.forRoute(route).prepare("", "steer", images)
        images.clear()
        assertEquals(originalImages, prepared.images)
        assertEquals(prepared, store().load().entries.single())
        assertEquals(prepared, outbox.pending(route).single())
        outbox.markSending(prepared.requestId, route)
        val recovered = open()
        val unknown = recovered.pending(route).single()
        assertEquals(prepared.copy(state = PromptDeliveryState.OUTCOME_UNKNOWN), unknown)
        val retry = recovered.retry(prepared.requestId, route)!!
        assertEquals(prepared, retry)
        assertEquals(prepared.route.ownerId, retry.route.ownerId)
        assertEquals(prepared.sessionId, retry.sessionId)
        originalImages.zip(retry.images).forEach { (original, restored) ->
            assertEquals(original.data, restored.data)
            assertArrayEquals(java.util.Base64.getDecoder().decode(original.data), java.util.Base64.getDecoder().decode(restored.data))
            assertEquals(original.mediaType, restored.mediaType)
            assertEquals(original.name, restored.name)
        }
        assertEquals(WireJson.encode(filePromptArguments(route.sessionId, prepared.requestId, "", emptyList(), "steer", prepared.images)),
            WireJson.encode(filePromptArguments(route.sessionId, retry.requestId, "", emptyList(), "steer", retry.images)))
        assertNull(recovered.retry(prepared.requestId, route.copy(ownerId = "different-owner")))
        assertNull(recovered.retry(prepared.requestId, route.copy(sessionId = "different-session")))
        assertEquals(listOf(prepared), recovered.pending(route))
    }

    @Test fun constructorCopiesAndStoreSnapshotsFreezeBothReceiptAndImageLists() = runBlocking {
        val receipts = mutableListOf("opaque receipt")
        val images = mutableListOf(nativeOutboxImage())
        val entry = PromptOutboxEntry("00000000-0000-4000-8000-000000000002", route, "", "queue", receipts = receipts, images = images)
        val copy = entry.copy(receipts = receipts, images = images)
        receipts[0] = "changed receipt"
        images.clear()
        assertEquals(listOf("opaque receipt"), entry.receipts)
        assertEquals(listOf(nativeOutboxImage()), entry.images)
        assertEquals(entry, copy)
        assertThrows(UnsupportedOperationException::class.java) { (entry.images as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (entry.receipts as MutableList<*>).clear() }
        val entries = mutableListOf(entry)
        val committed = store().commit(0, entries)
        entries.clear()
        assertEquals(listOf(entry), committed.entries)
        assertThrows(UnsupportedOperationException::class.java) { (committed.entries as MutableList<*>).clear() }
        val recovered = open()
        assertThrows(UnsupportedOperationException::class.java) { (recovered.snapshot.value as MutableList<*>).clear() }
        assertEquals(listOf(entry), recovered.pending(route))
    }

    @Test fun fourRealImagesAndTextReceiptBoundaryHaveIndependentPerIntentBudgets() = runBlocking {
        val images = List(NativeImagePart.MAX_IMAGES) {
            nativeOutboxImage(NativeImagePart.MAX_BATCH_ENCODED_BYTES / NativeImagePart.MAX_IMAGES, "image-$it.png")
        }
        val receipts = listOf("r".repeat(4096))
        val text = "t".repeat(DshCommandOutbox.MAX_TEXT_BYTES - 4096)
        val outbox = open()
        val entry = outbox.prepare(route, text, receipts = receipts, images = images)
        assertEquals(DshCommandOutbox.MAX_TEXT_BYTES, entry.payloadBytes)
        assertEquals(NativeImagePart.MAX_BATCH_ENCODED_BYTES, entry.imageBytes)
        assertEquals(entry, open().pending(route).single())
        val previous = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { outbox.prepare(route, text + "x", receipts = receipts, images = images) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { outbox.prepare(route, "", images = List(5) { nativeOutboxImage() }) } }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { outbox.prepare(route, "", images = listOf(nativeOutboxImage(NativeImagePart.MAX_BATCH_ENCODED_BYTES), nativeOutboxImage())) }
        }
        assertArrayEquals(previous, file.readBytes())
        assertEquals(listOf(entry), outbox.pending(route))
    }

    @Test fun totalImageBytesAreBoundedIndependentlyWithoutEvictingExistingIntents() = runBlocking {
        val outbox = open()
        val image = nativeOutboxImage(NativeImagePart.MAX_BATCH_ENCODED_BYTES)
        val prepared = List(DshCommandOutbox.MAX_TOTAL_IMAGE_BYTES / image.encodedBytes) {
            outbox.prepare(route, "", images = listOf(image))
        }
        val textOnly = outbox.prepare(route, "text does not consume the image budget")
        assertTrue(file.length() <= EncryptedFileOutboxStore.MAX_FILE_BYTES)
        val previous = file.readBytes()
        expectFailure(OutboxFailure.FULL) { outbox.prepare(route, "", images = listOf(nativeOutboxImage())) }
        assertArrayEquals(previous, file.readBytes())
        assertEquals(prepared + textOnly, open().pending(route))
    }

    private suspend fun expectFailure(failure: OutboxFailure, action: suspend () -> Any?) {
        try { action(); fail("expected $failure") }
        catch (error: OutboxException) { assertEquals(failure, error.failure) }
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean = needle.isNotEmpty() &&
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
}
