package cn.edu.ustc.tokenworks

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatReferenceCompletionsTest {
    @Test fun completionDoesNotTreatEmailOrSlashInProseAsReference() {
        for (text in listOf("me@example.test", "see /docs", "//", "@\"a file")) {
            assertNull(activeChatReference(TextFieldValue(text, TextRange(text.length))))
        }
    }

    @Test fun completionReplacesOnlyTheActiveTokenAndKeepsFollowingText() {
        val source = TextFieldValue("Review @docr and keep this", TextRange(10))
        val token = requireNotNull(activeChatReference(source))
        assertEquals("do", token.query)
        val updated = source.acceptChatReference(token, "@\"docs/file name\"")
        assertEquals("Review @\"docs/file name\" and keep this", updated.text)
        assertEquals(TextRange(24), updated.selection)
    }
}
