package cn.edu.ustc.tokenworks

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationPdfTest {
    @Test fun longConversationCanBeOpenedAsPaginatedPdf() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("conversation-test", ".pdf", context.cacheDir)
        try {
            val text = "A paragraph with Arabic أهلاً and Chinese 文本, and an inert link https://example.invalid.\n".repeat(120)
            val items = listOf(ConversationItem("1", ConversationRole.USER, listOf(DisplayBlock(BlockKind.TEXT, text)),
                live = false, persisted = true, seq = 1, time = 1710000000000L))
            file.outputStream().use { writeConversationPdfTo(context, ConversationCapture("Fixture", items, true), it) }
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(descriptor).use { pdf -> assertTrue(pdf.pageCount >= 3) }
        } finally { file.delete() }
    }
}
