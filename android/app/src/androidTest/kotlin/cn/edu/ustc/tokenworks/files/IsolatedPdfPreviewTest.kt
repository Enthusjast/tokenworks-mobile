package cn.edu.ustc.tokenworks

import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IsolatedPdfPreviewTest {
    @Test fun rendersOnlyTheRequestedPageFromAPrivateFileAndRejectsBadInput() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        withContext(Dispatchers.IO) {
            val original = File.createTempFile("pdf-lifecycle", ".private", context.cacheDir)
            try {
                val pdf = PdfDocument()
                try {
                    for ((index, color) in listOf(Color.RED, Color.BLUE).withIndex()) {
                        val page = pdf.startPage(PdfDocument.PageInfo.Builder(120, 80, index + 1).create())
                        page.canvas.drawColor(color)
                        pdf.finishPage(page)
                    }
                    original.outputStream().use(pdf::writeTo)
                } finally { pdf.close() }
                val second = renderPdfPreviewPage(context, original, 1)
                try {
                    assertEquals(2, second.pages)
                    assertFalse(second.truncated)
                    assertEquals(Color.BLUE, second.bitmap.getPixel(40, 40))
                } finally { second.bitmap.recycle() }
                assertTrue(original.isFile)
                original.writeText("%PDF-1.4 not a document")
                try { renderPdfPreviewPage(context, original, 0); fail("Malformed PDF must fail closed") }
                catch (_: IllegalArgumentException) { /* bounded fallback */ }
            } finally { original.delete() }
        }
    }
}
