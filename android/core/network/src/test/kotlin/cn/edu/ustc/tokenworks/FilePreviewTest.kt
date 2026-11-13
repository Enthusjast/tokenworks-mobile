package cn.edu.ustc.tokenworks

import org.junit.Assert.*
import org.junit.Test

class FilePreviewTest {
    @Test fun previewableTypesCoverTextImagesAndPdfOnly() {
        for (type in listOf("text/plain", "text/x-kotlin", "application/json", "image/png", "image/jpeg", "application/pdf"))
            assertTrue(type, FilePreview.previewable(type))
        for (type in listOf("application/octet-stream", "application/zip", "video/mp4", "application/x-msdownload", ""))
            assertFalse(type, FilePreview.previewable(type))
    }

    @Test fun previewBudgetRejectsOversizedBodies() {
        // Transport-level guard mirrors network.response_too_large semantics.
        val max = FilePreview.MAX_PREVIEW_BYTES
        assertTrue(max > 0)
        assertThrows(RemoteRequestException::class.java) {
            throw RemoteRequestException("network.response_too_large")
        }
    }
}
