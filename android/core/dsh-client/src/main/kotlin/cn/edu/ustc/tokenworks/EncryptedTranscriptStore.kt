package cn.edu.ustc.tokenworks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException
import java.util.WeakHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Never replace a missing key while reading existing ciphertext. */
fun interface TranscriptKeyProvider { fun key(create: Boolean): SecretKey }

/**
 * Local display-only persistence. Paths are opaque, full-route identity is GCM AAD, and only
 * ciphertext is ever written (including the atomic temporary file). Callers supply a no-backup root.
 * Both filesystem and key-provider calls are confined to IO, including deletion and corruption repair.
 */
class EncryptedTranscriptStore(
    private val directory: () -> File,
    private val keys: TranscriptKeyProvider,
) {
    constructor(directory: File, keys: TranscriptKeyProvider) : this({ directory }, keys)

    private val handleLock = Any()
    private val handles = WeakHashMap<Scoped, Unit>()
    private class Scope(val owner: String? = null, val host: String? = null, val runtime: String? = null) {
        fun contains(route: DshCommandRoute) = (owner == null || route.ownerId == owner) &&
            (host == null || route.hostId == host) && (runtime == null || route.runtimeId == runtime)
    }
    companion object {
        private val processLock = Any()
        private val domain = "tokenworks-mobile/dsh-transcript/v1\u0000".toByteArray(Charsets.UTF_8)
        private const val MAX_FILE_BYTES = 1024 * 1024
        private const val MAX_FILES = 64
    }

    fun forRoute(route: DshCommandRoute): DshTranscriptCache = synchronized(handleLock) {
        Scoped(route).also { handles[it] = Unit }
    }
    suspend fun clearOwner(ownerId: String) = clearScope(Scope(ownerId))
    suspend fun clearHost(ownerId: String, hostId: String) = clearScope(Scope(ownerId, hostId))
    suspend fun clearRuntime(ownerId: String, hostId: String, runtimeId: String) = clearScope(Scope(ownerId, hostId, runtimeId))
    suspend fun clearAll() = clearScope(Scope())

    private suspend fun clearScope(scope: Scope) = io {
        synchronized(handleLock) { handles.keys.filter { scope.contains(it.route) }.forEach { it.valid = false } }
        locked { root ->
            var target = root
            scope.owner?.let { target = File(target, pathPart(it)) }
            scope.host?.let { target = File(target, pathPart(it)) }
            scope.runtime?.let { target = File(target, pathPart(it)) }
            if (target != root) deleteTree(target)
            else root.listFiles()?.filter { it.name != ".lock" }?.forEach(::deleteTree)
            forceDirectory(root)
        }
    }

    private inner class Scoped(override val route: DshCommandRoute) : DshTranscriptCache {
        @Volatile var valid = true
        override suspend fun load(): DshTranscriptSnapshot? = io {
            if (!valid) return@io null
            locked { root ->
                val file = location(root, route)
                if (!file.exists()) return@locked null
                try {
                    if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() !in 28L..MAX_FILE_BYTES.toLong()) corrupt()
                    val encrypted = file.inputStream().use { input ->
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (bytes.size() + count > MAX_FILE_BYTES) corrupt()
                            bytes.write(buffer, 0, count)
                        }
                        bytes.toByteArray()
                    }
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, keys.key(false), GCMParameterSpec(128, encrypted, 0, 12))
                    cipher.updateAAD(aad(route))
                    val clear = cipher.doFinal(encrypted, 12, encrypted.size - 12)
                    try {
                        DshTranscriptSnapshot.parse(SessionJson.parse(clear)).also { require(it.identity.id == route.sessionId) }
                    } finally { clear.fill(0) }
                } catch (_: GeneralSecurityException) {
                    Files.deleteIfExists(file.toPath()); null
                } catch (_: IllegalArgumentException) {
                    Files.deleteIfExists(file.toPath()); null
                }
            }
        }

        override suspend fun save(snapshot: DshTranscriptSnapshot) = io {
            if (!valid) return@io
            require(snapshot.identity.id == route.sessionId)
            locked { root ->
                val file = location(root, route)
                val parent = file.parentFile!!
                ensureDirectories(root, parent)
                val clear = SessionJson.encode(snapshot.json())
                val encrypted = try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, keys.key(true))
                    cipher.updateAAD(aad(route))
                    require(cipher.iv.size == 12)
                    cipher.iv + cipher.doFinal(clear)
                } finally { clear.fill(0) }
                require(encrypted.size <= MAX_FILE_BYTES)
                val temporary = Files.createTempFile(parent.toPath(), ".transcript-", ".tmp").toFile()
                try {
                    if (!temporary.setReadable(false, false) || !temporary.setWritable(false, false) ||
                        !temporary.setReadable(true, true) || !temporary.setWritable(true, true)) throw java.io.IOException("cache permissions")
                    FileOutputStream(temporary).use { output -> output.write(encrypted); output.fd.sync() }
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    forceDirectory(parent)
                    evict(root, file)
                } finally { Files.deleteIfExists(temporary.toPath()) }
            }
        }

        override suspend fun clear() = io {
            synchronized(handleLock) { handles.keys.filter { it.route == route }.forEach { it.valid = false } }
            valid = false
            locked { root ->
                val file = location(root, route)
                Files.deleteIfExists(file.toPath())
                if (file.parentFile.isDirectory) forceDirectory(file.parentFile)
            }
        }
    }

    private suspend fun <T> io(action: () -> T): T = withContext(Dispatchers.IO) { synchronized(processLock) { action() } }

    private fun <T> locked(action: (File) -> T): T {
        val root = directory().absoluteFile
        if (Files.isSymbolicLink(root.toPath())) throw java.io.IOException("cache symlink")
        Files.createDirectories(root.toPath())
        val lock = File(root, ".lock")
        if (Files.isSymbolicLink(lock.toPath())) throw java.io.IOException("cache symlink")
        return FileChannel.open(lock.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                // Interrupted atomic replacements leave ciphertext only; remove those orphans under the same lock.
                root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
                    .filter { it.isFile && it.extension == "tmp" }.forEach { Files.deleteIfExists(it.toPath()) }
                action(root)
            }
        }
    }
    private fun location(root: File, route: DshCommandRoute): File {
        val parent = File(File(File(root, pathPart(route.ownerId)), pathPart(route.hostId)), pathPart(route.runtimeId))
        var current = parent
        while (current != root) {
            if (Files.isSymbolicLink(current.toPath())) throw java.io.IOException("cache symlink")
            current = current.parentFile
        }
        return File(parent, pathPart(route.sessionId) + ".bin")
    }
    private fun ensureDirectories(root: File, directory: File) {
        val missing = ArrayList<File>()
        var parent = directory
        while (parent != root && !parent.isDirectory) { missing += parent; parent = parent.parentFile }
        for (entry in missing.asReversed()) { Files.createDirectory(entry.toPath()); forceDirectory(entry.parentFile) }
    }
    private fun evict(root: File, keep: File) {
        val files = root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.filter { it.isFile && it.extension == "bin" }.toList()
        files.filter { it != keep }.sortedBy { it.lastModified() }.take((files.size - MAX_FILES).coerceAtLeast(0)).forEach {
            Files.deleteIfExists(it.toPath()); forceDirectory(it.parentFile)
        }
    }
    private fun deleteTree(file: File) {
        if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) file.listFiles()?.forEach(::deleteTree)
        Files.deleteIfExists(file.toPath())
    }
    private fun forceDirectory(file: File) = FileChannel.open(file.toPath(), StandardOpenOption.READ).use { it.force(true) }
    private fun pathPart(value: String) = transcriptDigest(value.toByteArray(Charsets.UTF_8))
    private fun aad(route: DshCommandRoute) = domain + SessionJson.encode(jsonObject(
        "owner" to route.ownerId.json(), "host" to route.hostId.json(), "runtime" to route.runtimeId.json(), "session" to route.sessionId.json()))
    private fun corrupt(): Nothing = throw IllegalArgumentException("data.corrupt")
}
