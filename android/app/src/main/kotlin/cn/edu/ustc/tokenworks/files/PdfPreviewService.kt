package cn.edu.ustc.tokenworks

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val RENDER = 1
private const val COMPLETE = 2
private const val INPUT = "input"
private const val OUTPUT = "output"
private const val MAX_EDGE = 1400
private const val MAX_PAGE = 128

/** The isolated UID receives only two ephemeral descriptors; it cannot read app data or use its network permissions. */
class PdfPreviewService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var receiver: Messenger

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("pdf-preview").apply { start() }
        receiver = Messenger(Handler(thread.looper) { message ->
            if (message.what != RENDER) return@Handler false
            val ok = runCatching {
                val data = message.data
                data.classLoader = ParcelFileDescriptor::class.java.classLoader
                @Suppress("DEPRECATION") val input = data.getParcelable<ParcelFileDescriptor>(INPUT) ?: error("Missing PDF")
                input.use {
                    @Suppress("DEPRECATION") val output = data.getParcelable<ParcelFileDescriptor>(OUTPUT) ?: error("Missing output")
                    ParcelFileDescriptor.AutoCloseOutputStream(output).use { raw ->
                        DataOutputStream(BufferedOutputStream(raw)).use { renderPdf(input, it, message.arg1) }
                    }
                }
            }.isSuccess
            runCatching { message.replyTo?.send(Message.obtain(null, COMPLETE, if (ok) 1 else 0, 0)) }
            true
        })
    }

    override fun onBind(intent: Intent): IBinder = receiver.binder
    override fun onDestroy() { thread.quitSafely(); super.onDestroy() }
}

private fun renderPdf(input: ParcelFileDescriptor, output: DataOutputStream, index: Int) {
    require(input.statSize in 1..FilePreview.MAX_PREVIEW_BYTES.toLong())
    val owned = ParcelFileDescriptor.dup(input.fileDescriptor)
    val renderer = try { PdfRenderer(owned) } catch (error: Throwable) { owned.close(); throw error }
    renderer.use { pdf ->
        val pages = pdf.pageCount
        require(index in 0 until minOf(pages, MAX_PAGE))
        pdf.openPage(index).use { page ->
            require(page.width > 0 && page.height > 0)
            val scale = minOf(1.0, MAX_EDGE.toDouble() / maxOf(page.width, page.height))
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                output.writeInt(pages)
                output.writeInt(width)
                output.writeInt(height)
                val pixels = IntArray(width)
                val row = ByteBuffer.allocate(width * Int.SIZE_BYTES)
                val ints = row.asIntBuffer()
                repeat(height) { y ->
                    bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
                    ints.position(0)
                    ints.put(pixels)
                    output.write(row.array())
                }
            } finally { bitmap.recycle() }
        }
    }
}

internal data class PdfPreviewPage(val bitmap: Bitmap, val pages: Int, val truncated: Boolean)

/** No content URI or network route is exposed to the renderer or required from the caller. */
internal suspend fun renderPdfPreviewPage(context: Context, input: File, page: Int): PdfPreviewPage {
    require(page in 0 until MAX_PAGE && input.length() in 1..FilePreview.MAX_PREVIEW_BYTES.toLong())
    val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
    var rendered: PdfPreviewPage? = null
    try {
        readEnd.use { reader ->
            writeEnd.use { writer ->
                ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                    val fd = reader.fileDescriptor
                    require(withTimeoutOrNull(8_000) {
                        val binding = async { bindPdfRenderer(context.applicationContext, source, writer, page) }
                        rendered = readPdfBitmap(fd, page)
                        binding.await()
                    } == true) { "PDF renderer did not complete" }
                }
            }
        }
        return checkNotNull(rendered)
    } catch (error: Throwable) { rendered?.bitmap?.recycle(); throw error }
}

private suspend fun readPdfBitmap(fd: FileDescriptor, page: Int): PdfPreviewPage {
    val header = ByteArray(3 * Int.SIZE_BYTES)
    readExactly(fd, header)
    val values = ByteBuffer.wrap(header)
    val pages = values.int
    val width = values.int
    val height = values.int
    require(page in 0 until minOf(pages, MAX_PAGE) && width in 1..MAX_EDGE && height in 1..MAX_EDGE)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        val bytes = ByteArray(width * Int.SIZE_BYTES)
        val row = ByteBuffer.wrap(bytes).asIntBuffer()
        val pixels = IntArray(width)
        repeat(height) { y ->
            readExactly(fd, bytes)
            row.position(0)
            row.get(pixels)
            bitmap.setPixels(pixels, 0, width, 0, y, width, 1)
        }
        require(readPipe(fd, bytes, 0, 1) == 0)
        return PdfPreviewPage(bitmap, minOf(pages, MAX_PAGE), pages > MAX_PAGE)
    } catch (error: Throwable) { bitmap.recycle(); throw error }
}

private suspend fun readExactly(fd: FileDescriptor, bytes: ByteArray) {
    var offset = 0
    while (offset < bytes.size) {
        val count = readPipe(fd, bytes, offset, bytes.size - offset)
        require(count > 0) { "Truncated PDF preview" }
        offset += count
    }
}

private suspend fun readPipe(fd: FileDescriptor, bytes: ByteArray, offset: Int, size: Int): Int {
    val wait = arrayOf(StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() })
    while (true) {
        currentCoroutineContext().ensureActive()
        try {
            if (Os.poll(wait, 40) == 0) {
                kotlinx.coroutines.yield()
                continue
            }
            // This pipe has one reader, so readable data cannot be consumed before this read.
            return Os.read(fd, bytes, offset, size)
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.EINTR) throw error
        }
    }
}

private suspend fun bindPdfRenderer(context: Context, input: ParcelFileDescriptor, output: ParcelFileDescriptor, page: Int): Boolean =
    withContext(Dispatchers.Main.immediate) {
        var bound = false
        var connection: ServiceConnection? = null
        try {
            suspendCancellableCoroutine { result ->
                val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                    if (message.what == COMPLETE && result.isActive) result.resume(message.arg1 == 1)
                    true
                })
                fun failBinding() {
                    runCatching { output.close() }
                    if (result.isActive) result.resume(false)
                }
                val listener = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        if (!result.isActive) return
                        try {
                            Messenger(binder).send(Message.obtain(null, RENDER, page, 0).apply {
                                replyTo = reply
                                data = Bundle().apply { putParcelable(INPUT, input); putParcelable(OUTPUT, output) }
                            })
                        } catch (error: Exception) { if (result.isActive) result.resumeWithException(error) }
                        finally { runCatching { output.close() } }
                    }
                    override fun onServiceDisconnected(name: ComponentName) = failBinding()
                    override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
                    override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
                }
                connection = listener
                bound = context.bindService(Intent(context, PdfPreviewService::class.java), listener, Context.BIND_AUTO_CREATE)
                if (!bound) failBinding()
            }
        } finally {
            if (bound) connection?.let(context::unbindService)
        }
    }
