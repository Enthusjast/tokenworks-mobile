package cn.edu.ustc.tokenworks

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.floor
import kotlin.math.min

/** A new, metadata-free copy; the source stream is read once and never modified. */
data class PreparedNativeImage(val part: NativeImagePart, val width: Int, val height: Int, val correctedOrientation: Boolean, val reducedSize: Boolean)

internal class NativeImagePreparationException(val code: String) : IllegalArgumentException(code)

class NativeImagePreprocessor {
    fun preprocess(name: String, source: () -> InputStream): PreparedNativeImage {
        val bytes = source().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer, 0, min(buffer.size, MAX_SOURCE + 1 - output.size()))
                if (count < 0) break
                if (count == 0) {
                    val next = input.read()
                    if (next < 0) break
                    if (output.size() >= MAX_SOURCE) throw NativeImagePreparationException("files.image_source_too_large")
                    output.write(next)
                } else {
                    if (count > MAX_SOURCE - output.size()) throw NativeImagePreparationException("files.image_source_too_large")
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        }
        val type = inspect(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..16384 || bounds.outHeight !in 1..16384 ||
            bounds.outWidth.toLong() * bounds.outHeight > 32_000_000L) throw NativeImagePreparationException("files.image_dimensions")
        val orientation = if (type != "gif") {
            try {
                if (type == "jpeg") ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                else androidx.exifinterface.media.ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
            } catch (_: Exception) { throw NativeImagePreparationException("files.image_orientation") }
        } else 1
        if (orientation !in 0..8) throw NativeImagePreparationException("files.image_orientation")
        var sample = 1
        while ((bounds.outWidth + sample - 1) / sample > 1600 || (bounds.outHeight + sample - 1) / sample > 1600) sample *= 2
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw NativeImagePreparationException("files.image_invalid")
        try {
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }
                    6 -> setRotate(90f)
                    7 -> { setRotate(90f); postScale(1f, -1f) }
                    8 -> setRotate(270f)
                }
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) { bitmap.recycle(); bitmap = rotated }
            }
            val alpha = bitmap.hasAlpha()
            val format = if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            // Each scale has a fixed, finite quality ladder; alpha is never flattened to JPEG.
            for (scale in 0 until 12) {
                for (quality in if (alpha) listOf(100) else listOf(90, 75, 55, 35)) {
                    val output = CappedOutput()
                    try {
                        if (!bitmap.compress(format, quality, output)) throw NativeImagePreparationException("files.image_encode")
                    } catch (_: EncodedLimit) { continue }
                    val encoded = output.toByteArray()
                    val data = Base64.encodeToString(encoded, Base64.NO_WRAP)
                    if (data.length > 256 * 1024) throw NativeImagePreparationException("files.image_encoded_too_large")
                    val orientedWidth = if (orientation in 5..8) bounds.outHeight else bounds.outWidth
                    val orientedHeight = if (orientation in 5..8) bounds.outWidth else bounds.outHeight
                    val changedSize = bitmap.width != orientedWidth || bitmap.height != orientedHeight
                    return PreparedNativeImage(NativeImagePart(if (alpha) "image/png" else "image/jpeg", data, name),
                        bitmap.width, bitmap.height, orientation in 2..8, changedSize)
                }
                if (bitmap.width == 1 && bitmap.height == 1) break
                val smaller = Bitmap.createScaledBitmap(bitmap, maxOf(1, floor(bitmap.width * 0.75).toInt()), maxOf(1, floor(bitmap.height * 0.75).toInt()), true)
                if (smaller !== bitmap) { bitmap.recycle(); bitmap = smaller }
            }
            throw NativeImagePreparationException("files.image_encoded_too_large")
        } finally { bitmap.recycle() }
    }

    private class EncodedLimit : RuntimeException()
    private class CappedOutput : ByteArrayOutputStream() {
        override fun write(value: Int) { if (count >= MAX_ENCODED) throw EncodedLimit(); super.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length > MAX_ENCODED - count) throw EncodedLimit()
            super.write(bytes, offset, length)
        }
    }

    private fun inspect(b: ByteArray): String {
        fun invalid(): Nothing = throw NativeImagePreparationException("files.image_invalid")
        fun animated(): Nothing = throw NativeImagePreparationException("files.image_animated")
        fun text(at: Int, size: Int) = if (at >= 0 && size <= b.size - at) String(b, at, size, Charsets.ISO_8859_1) else ""
        fun u(at: Int) = b[at].toInt() and 255
        fun le(at: Int): Long = (0..3).fold(0L) { n, i -> n or (u(at + i).toLong() shl (8 * i)) }
        fun be(at: Int): Long = (0..3).fold(0L) { n, i -> (n shl 8) or u(at + i).toLong() }
        if (b.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { b[it] == PNG_SIGNATURE[it] }) {
            var p = 8
            var header = false
            var pixels = false
            while (p <= b.size - 12) {
                val length = be(p)
                if (length > b.size - p - 12) invalid()
                val kind = text(p + 4, 4)
                if (!header && (kind != "IHDR" || length != 13L)) invalid()
                header = true
                if (kind == "acTL" || kind == "fcTL" || kind == "fdAT") animated()
                val crc = java.util.zip.CRC32().apply { update(b, p + 4, length.toInt() + 4) }
                if (crc.value != be(p + 8 + length.toInt())) invalid()
                if (kind == "IDAT") pixels = true
                p += length.toInt() + 12
                if (kind == "IEND") { if (length != 0L || p != b.size || !pixels) invalid(); return "png" }
            }
            invalid()
        }
        if (b.size >= 4 && u(0) == 255 && u(1) == 216) {
            if (u(b.size - 2) != 255 || u(b.size - 1) != 217) invalid()
            return "jpeg"
        }
        if (b.size >= 12 && text(0, 4) == "RIFF" && text(8, 4) == "WEBP") {
            if (le(4) + 8 != b.size.toLong()) invalid()
            var p = 12
            var pixels = false
            while (p <= b.size - 8) {
                val kind = text(p, 4)
                val length = le(p + 4)
                if (length > b.size - p - 8) invalid()
                if (kind == "ANIM" || kind == "ANMF" || (kind == "VP8X" && length > 0 && u(p + 8) and 2 != 0)) animated()
                if (kind == "VP8 " || kind == "VP8L") pixels = true
                p += 8 + length.toInt() + (length.toInt() and 1)
            }
            if (p != b.size || !pixels) invalid()
            return "webp"
        }
        if (b.size >= 13 && text(0, 6) in listOf("GIF87a", "GIF89a")) {
            var p = 13 + if (u(10) and 128 != 0) 3 * (1 shl ((u(10) and 7) + 1)) else 0
            var frames = 0
            fun blocks() {
                while (p < b.size) { val size = u(p++); if (size == 0) return; if (size > b.size - p) invalid(); p += size }
                invalid()
            }
            while (p < b.size) {
                when (u(p++)) {
                    0x3b -> { if (frames != 1 || p != b.size) invalid(); return "gif" }
                    0x21 -> { if (p >= b.size) invalid(); p++; blocks() }
                    0x2c -> {
                        if (++frames > 1) animated()
                        if (p > b.size - 9) invalid()
                        val packed = u(p + 8); p += 9
                        if (packed and 128 != 0) p += 3 * (1 shl ((packed and 7) + 1))
                        if (p >= b.size || u(p++) !in 2..8) invalid()
                        blocks()
                    }
                    else -> invalid()
                }
            }
            invalid()
        }
        invalid()
    }

    private companion object {
        const val MAX_SOURCE = 16 * 1024 * 1024
        const val MAX_ENCODED = 192 * 1024
        val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    }
}
