package cn.edu.ustc.tokenworks

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Random
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeImagePreprocessorTest {
    private val processor = NativeImagePreprocessor()
    private fun prepare(bytes: ByteArray, name: String = "fixture.bin") = processor.preprocess(name) { bytes.inputStream() }
    private fun decode(image: PreparedNativeImage): Bitmap {
        val bytes = Base64.decode(image.part.data, Base64.NO_WRAP)
        assertTrue(bytes.size <= 192 * 1024)
        assertTrue(image.part.data.length <= 256 * 1024)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
    }
    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat): ByteArray =
        ByteArrayOutputStream().also { assertTrue(bitmap.compress(format, 100, it)) }.toByteArray()
    private fun quadrants(): Bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply {
        setHasAlpha(false)
        for (y in 0 until height) for (x in 0 until width)
            setPixel(x, y, listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)[(if (y >= 20) 2 else 0) + (if (x >= 40) 1 else 0)])
    }
    private fun jpegWithOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        // APP1 Exif: little-endian TIFF, one SHORT Orientation entry, no next IFD.
        val tiff = byteArrayOf(73, 73, 42, 0, 8, 0, 0, 0, 1, 0,
            18, 1, 3, 0, 1, 0, 0, 0, orientation.toByte(), 0, 0, 0, 0, 0, 0, 0)
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
        return jpeg.copyOfRange(0, 2) + byteArrayOf(-1, -31, 0, (payload.size + 2).toByte()) + payload + jpeg.copyOfRange(2, jpeg.size)
    }
    private fun assertColor(expected: Int, actual: Int) {
        assertTrue("red channel", kotlin.math.abs(Color.red(expected) - Color.red(actual)) < 45)
        assertTrue("green channel", kotlin.math.abs(Color.green(expected) - Color.green(actual)) < 45)
        assertTrue("blue channel", kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) < 45)
    }
    private fun rejected(bytes: ByteArray) {
        try { prepare(bytes); fail("Expected image rejection") } catch (expected: NativeImagePreparationException) {
            assertTrue(expected.code.startsWith("files.image_"))
        }
    }

    @Test fun allEightExifOrientationsTransformActualPixels() {
        val original = quadrants()
        val jpeg = try { encode(original, Bitmap.CompressFormat.JPEG) } finally { original.recycle() }
        val corners = listOf(
            listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0), listOf(2, 3, 0, 1),
            listOf(0, 2, 1, 3), listOf(2, 0, 3, 1), listOf(3, 1, 2, 0), listOf(1, 3, 0, 2),
        )
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        for (orientation in 1..8) {
            val source = jpegWithOrientation(jpeg, orientation)
            val snapshot = source.copyOf()
            val result = prepare(source)
            assertArrayEquals(snapshot, source)
            assertEquals(if (orientation >= 5) 40 else 80, result.width)
            assertEquals(if (orientation >= 5) 80 else 40, result.height)
            val bitmap = decode(result)
            try {
                val points = listOf(5 to 5, bitmap.width - 6 to 5, 5 to bitmap.height - 6, bitmap.width - 6 to bitmap.height - 6)
                points.forEachIndexed { index, point -> assertColor(colors[corners[orientation - 1][index]], bitmap.getPixel(point.first, point.second)) }
            } finally { bitmap.recycle() }
            val output = Base64.decode(result.part.data, Base64.NO_WRAP)
            assertFalse(String(output, Charsets.ISO_8859_1).contains("Exif"))
            assertEquals(orientation != 1, result.correctedOrientation)
        }
    }

    @Test fun pngAndWebpAreSniffedAndKeepTransparentPixels() {
        val original = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT); setPixel(16, 12, Color.argb(120, 200, 30, 80))
        }
        try {
            for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP)) {
                val result = prepare(encode(original, format), "misleading.jpeg")
                assertEquals("image/png", result.part.mediaType)
                val output = decode(result)
                try {
                    assertEquals(0, Color.alpha(output.getPixel(0, 0)))
                    assertTrue(Color.alpha(output.getPixel(16, 12)) in 110..130)
                } finally { output.recycle() }
                assertFalse(result.reducedSize)
            }
        } finally { original.recycle() }
    }

    @Test fun noisyAlphaImageIsBoundedWithoutFlattening() {
        val bitmap = Bitmap.createBitmap(1800, 1600, Bitmap.Config.ARGB_8888)
        val random = Random(71)
        val row = IntArray(bitmap.width)
        try {
            for (y in 0 until bitmap.height) {
                for (x in row.indices) row[x] = random.nextInt()
                bitmap.setPixels(row, 0, row.size, 0, y, row.size, 1)
            }
            val result = prepare(encode(bitmap, Bitmap.CompressFormat.PNG))
            assertTrue(result.width <= 1600 && result.height <= 1600)
            assertTrue(result.width.toLong() * result.height <= 2_560_000)
            val output = decode(result)
            try { assertTrue(output.hasAlpha()); assertTrue(Color.alpha(output.getPixel(0, 0)) < 255) }
            finally { output.recycle() }
            assertTrue(result.reducedSize)
        } finally { bitmap.recycle() }
    }

    @Test fun staticGifIsAcceptedButMultipleFramesAreRejected() {
        val header = byteArrayOf(71, 73, 70, 56, 57, 97, 1, 0, 1, 0, -128, 0, 0, 0, 0, 0, -1, -1, -1)
        val frame = byteArrayOf(44, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 68, 1, 0)
        val result = prepare(header + frame + byteArrayOf(59))
        val output = decode(result)
        try { assertEquals(1, output.width); assertEquals(1, output.height) } finally { output.recycle() }
        rejected(header + frame + frame + byteArrayOf(59))
    }

    @Test fun unsupportedCorruptAndAnimatedContainersAreRejected() {
        rejected("not an image".toByteArray())
        val bitmap = quadrants()
        val png: ByteArray
        val jpeg: ByteArray
        try { png = encode(bitmap, Bitmap.CompressFormat.PNG); jpeg = encode(bitmap, Bitmap.CompressFormat.JPEG) }
        finally { bitmap.recycle() }
        rejected(png.copyOf(png.size - 1))
        rejected(jpeg.copyOf(jpeg.size - 2))
        rejected(png.copyOf().apply { this[29] = (this[29].toInt() xor 1).toByte() })
        rejected(png.copyOfRange(0, 33) + chunk("acTL", byteArrayOf(0, 0, 0, 2, 0, 0, 0, 0)) + png.copyOfRange(33, png.size))
        val webp = "RIFF".toByteArray() + byteArrayOf(16, 0, 0, 0) + "WEBPANIM".toByteArray() + byteArrayOf(4, 0, 0, 0, 0, 0, 0, 0)
        rejected(webp)
    }

    @Test fun sourceReadIsCappedAndClosed() {
        var read = 0
        var closed = false
        val stream = object : InputStream() {
            override fun read(): Int { read++; return 0 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                java.util.Arrays.fill(bytes, offset, offset + length, 0.toByte()); read += length; return length
            }
            override fun close() { closed = true }
        }
        try { processor.preprocess("huge") { stream }; fail("Expected size rejection") }
        catch (_: IllegalArgumentException) { assertEquals(16 * 1024 * 1024 + 1, read); assertTrue(closed) }
    }

    @Test fun excessiveDimensionsAndPixelCountAreRejectedBeforePixelDecode() {
        for ((width, height) in listOf(16385 to 1, 8000 to 8000)) {
            val data = ByteArrayOutputStream().apply {
                write(intBytes(width)); write(intBytes(height)); write(byteArrayOf(8, 6, 0, 0, 0))
            }.toByteArray()
            rejected(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", data) +
                chunk("IDAT", byteArrayOf(120, -100, 3, 0, 0, 0, 0, 1)) + chunk("IEND", byteArrayOf()))
        }
    }
    private fun intBytes(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    private fun chunk(type: String, data: ByteArray): ByteArray {
        val body = type.toByteArray(Charsets.US_ASCII) + data
        return intBytes(data.size) + body + intBytes(CRC32().apply { update(body) }.value.toInt())
    }
}
