package cn.edu.ustc.tokenworks

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.NoSuchFileException
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Never generate a replacement key when create=false: missing keys must fail closed. */
fun interface OutboxKeyProvider { fun key(create: Boolean): SecretKey }

/**
 * Encrypted manifest plus individually route-authenticated entries. Neither prompts nor routing
 * identities are visible on disk. Atomic replacement is required (no non-atomic fallback).
 */
class EncryptedFileOutboxStore internal constructor(
    private val file: File,
    private val keys: OutboxKeyProvider,
    private val beforeReplace: () -> Unit,
) : DshOutboxStore {
    constructor(file: File, keys: OutboxKeyProvider) : this(file, keys, {})

    companion object {
        internal const val MAX_FILE_BYTES = 10 * 1024 * 1024
        private const val VERSION = 3
        private const val LEGACY_VERSION = 2
        private const val MAX_ENTRY_BYTES = DshCommandOutbox.MAX_TEXT_BYTES + NativeImagePart.MAX_BATCH_ENCODED_BYTES + 4096
        private val DOMAIN = "tokenworks-mobile/dsh-command-outbox/v2".toByteArray(Charsets.UTF_8)
        private val ENTRY_DOMAIN = "tokenworks-mobile/dsh-command-entry/v2".toByteArray(Charsets.UTF_8)
        // JVM synchronization prevents OverlappingFileLockException; the OS lock covers processes.
        private val processLock = Any()
    }

    override fun load(): OutboxCheckpoint = locked { readLocked() }

    override fun commit(expectedRevision: Long, entries: List<PromptOutboxEntry>): OutboxCheckpoint = locked {
        val savedEntries = java.util.Collections.unmodifiableList(ArrayList(entries))
        DshCommandOutbox.validate(savedEntries)
        val previous = readLocked()
        if (previous.revision != expectedRevision) throw OutboxException(OutboxFailure.CONFLICT)
        if (expectedRevision == Long.MAX_VALUE) throw OutboxException(OutboxFailure.FULL)
        val next = OutboxCheckpoint(expectedRevision + 1, savedEntries)
        val key = keys.key(true)
        val clear = encode(next, key)
        val bytes = try { seal(clear, DOMAIN, key) } finally { clear.fill(0) }
        if (bytes.size > MAX_FILE_BYTES) throw OutboxException(OutboxFailure.FULL)
        val directory = file.absoluteFile.parentFile!!
        val temporary = Files.createTempFile(directory.toPath(), ".outbox-", ".tmp").toFile()
        try {
            if (!temporary.setReadable(false, false) || !temporary.setWritable(false, false) ||
                !temporary.setReadable(true, true) || !temporary.setWritable(true, true)) throw OutboxException(OutboxFailure.UNAVAILABLE)
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            beforeReplace()
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
        next
    }

    private fun <T> locked(action: () -> T): T = synchronized(processLock) {
        try {
            val directory = file.absoluteFile.parentFile!!
            if (Files.isSymbolicLink(directory.toPath()) || Files.isSymbolicLink(file.toPath())) throw OutboxException(OutboxFailure.UNAVAILABLE)
            if (!directory.isDirectory) {
                Files.createDirectories(directory.toPath())
                FileChannel.open(directory.parentFile.toPath(), StandardOpenOption.READ).use { it.force(true) }
            }
            val lock = File(directory, file.name + ".lock")
            if (Files.isSymbolicLink(lock.toPath())) throw OutboxException(OutboxFailure.UNAVAILABLE)
            FileChannel.open(lock.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { action() }
            }
        } catch (error: OutboxException) { throw error }
        catch (_: GeneralSecurityException) { throw OutboxException(OutboxFailure.CORRUPT) }
        catch (_: IOException) { throw OutboxException(OutboxFailure.UNAVAILABLE) }
    }

    private fun readLocked(): OutboxCheckpoint {
        val attributes = try {
            Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        } catch (_: NoSuchFileException) {
            return OutboxCheckpoint(0, emptyList())
        }
        if (!attributes.isRegularFile || attributes.size() !in 28L..MAX_FILE_BYTES.toLong()) corrupt()
        val encrypted = file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_FILE_BYTES) corrupt()
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val key = keys.key(false)
        val clear = open(encrypted, DOMAIN, key)
        return try {
            decode(clear, key).also { DshCommandOutbox.validate(it.entries) }
        } catch (_: EOFException) { corrupt() }
        catch (_: IllegalArgumentException) { corrupt() }
        catch (_: OutboxException) { corrupt() }
        finally { clear.fill(0) }
    }

    private fun encode(checkpoint: OutboxCheckpoint, key: SecretKey): ByteArray = binary { output ->
        output.writeInt(VERSION)
        output.writeLong(checkpoint.revision)
        output.writeInt(checkpoint.entries.size)
        checkpoint.entries.forEach { entry ->
            val route = routeBytes(entry.route)
            output.bytes(route)
            val clear = binary { payload ->
                payload.text(entry.requestId)
                payload.text(entry.text)
                payload.text(entry.mode)
                payload.writeInt(entry.state.ordinal)
                payload.writeBoolean(entry.neverAttempted)
                payload.writeInt(entry.receipts.size)
                entry.receipts.forEach { payload.text(it) }
                payload.writeInt(entry.images.size)
                entry.images.forEach { image ->
                    payload.text(image.mediaType)
                    payload.text(image.data)
                    payload.writeBoolean(image.name != null)
                    image.name?.let { payload.text(it) }
                }
            }
            try { output.bytes(seal(clear, ENTRY_DOMAIN + route, key)) } finally { clear.fill(0) }
        }
    }

    private fun decode(bytes: ByteArray, key: SecretKey): OutboxCheckpoint = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val version = input.readInt()
        if (version != LEGACY_VERSION && version != VERSION) corrupt()
        val revision = input.readLong()
        if (revision <= 0) corrupt()
        val count = input.readInt()
        if (count !in 0..DshCommandOutbox.MAX_ENTRIES) corrupt()
        val entries = ArrayList<PromptOutboxEntry>(count)
        repeat(count) {
            val routeBytes = input.bytes(1024)
            val route = DataInputStream(ByteArrayInputStream(routeBytes)).use { routeInput ->
                DshCommandRoute(routeInput.text(512), routeInput.text(128), routeInput.text(128), routeInput.text(128)).also {
                    if (routeInput.read() != -1) corrupt()
                }
            }
            val clear = open(input.bytes(MAX_ENTRY_BYTES), ENTRY_DOMAIN + routeBytes, key)
            try {
                DataInputStream(ByteArrayInputStream(clear)).use { payload ->
                    val requestId = payload.text(36)
                    val text = payload.text(DshCommandOutbox.MAX_TEXT_BYTES)
                    val mode = payload.text(16)
                    val state = PromptDeliveryState.entries.getOrNull(payload.readInt()) ?: corrupt()
                    // A legacy PENDING entry may already have been retried under this request ID.
                    val neverAttempted = if (version == LEGACY_VERSION) false else when (payload.readUnsignedByte()) {
                        0 -> false
                        1 -> true
                        else -> corrupt()
                    }
                    val receiptCount = payload.readInt()
                    if (receiptCount !in 0..16) corrupt()
                    val receipts = List(receiptCount) { payload.text(4096) }
                    val imageCount = payload.readInt()
                    if (imageCount !in 0..NativeImagePart.MAX_IMAGES) corrupt()
                    var remainingImageBytes = NativeImagePart.MAX_BATCH_ENCODED_BYTES
                    val images = List(imageCount) {
                        val mediaType = payload.text(32)
                        val data = payload.text(remainingImageBytes)
                        remainingImageBytes -= data.length
                        val hasName = payload.readUnsignedByte()
                        if (hasName !in 0..1) corrupt()
                        val name = if (hasName == 1) payload.text(NativeImagePart.MAX_NAME_BYTES) else null
                        NativeImagePart(mediaType, data, name)
                    }
                    if (payload.read() != -1) corrupt()
                    entries.add(PromptOutboxEntry(requestId, route, text, mode, state, receipts, images, neverAttempted))
                }
            } finally { clear.fill(0) }
        }
        if (input.read() != -1) corrupt()
        OutboxCheckpoint(revision, java.util.Collections.unmodifiableList(entries))
    }

    private fun routeBytes(route: DshCommandRoute) = binary {
        it.text(route.ownerId); it.text(route.hostId); it.text(route.runtimeId); it.text(route.sessionId)
    }

    private fun seal(clear: ByteArray, aad: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Let the provider generate the nonce: AndroidKeyStore prohibits caller-provided encryption IVs.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        check(cipher.iv.size == 12)
        return cipher.iv + cipher.doFinal(clear)
    }

    private fun open(bytes: ByteArray, aad: ByteArray, key: SecretKey): ByteArray {
        if (bytes.size < 28) corrupt()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
        cipher.updateAAD(aad)
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }

    private fun binary(write: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use(write)
        bytes.toByteArray()
    }
    private fun DataOutputStream.text(value: String) = bytes(value.toByteArray(Charsets.UTF_8))
    private fun DataOutputStream.bytes(value: ByteArray) { writeInt(value.size); write(value) }
    private fun DataInputStream.bytes(maximum: Int): ByteArray {
        val size = readInt()
        if (size !in 0..maximum || size > available()) corrupt()
        return ByteArray(size).also { readFully(it) }
    }
    private fun DataInputStream.text(maximum: Int): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes(maximum))).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { corrupt() }
    private fun corrupt(): Nothing = throw OutboxException(OutboxFailure.CORRUPT)
}
