package cn.edu.ustc.tokenworks

import android.content.ContentResolver
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.os.ConfigurationCompat
import java.io.IOException
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ConversationCapture(val title: String, val items: List<ConversationItem>, val partial: Boolean)

/** Snapshot only messages already present and confirmed in the transcript. No history/network fetches. */
internal fun captureConversation(title: String, read: SessionReadState): ConversationCapture? {
    val transcript = read.window ?: return null
    val items = transcript.items.filter { it.persisted && !it.live && it.seq >= 0 }
    return if (items.isEmpty()) null else ConversationCapture(title, items, transcript.hasMore || items.size != transcript.items.size)
}

internal fun conversationRoleLabel(role: ConversationRole): Int = when (role) {
    ConversationRole.USER -> R.string.conversation_message_role_user
    ConversationRole.ASSISTANT -> R.string.conversation_message_role_assistant
    ConversationRole.TOOL -> R.string.conversation_message_role_tool
    ConversationRole.NOTICE -> R.string.conversation_message_role_notice
}

/** Call off the main thread. Uses Android fonts and bidi line breaking; links remain inert text. */
internal suspend fun writeConversationPdf(context: Context, destination: Uri, capture: ConversationCapture) {
    require(destination.scheme == ContentResolver.SCHEME_CONTENT)
    renderConversationPdf(context, capture) { document ->
        val output = context.contentResolver.openOutputStream(destination, "wt") ?: throw IOException("PDF destination unavailable")
        output.use(document::writeTo)
    }
}

internal suspend fun writeConversationPdfTo(context: Context, capture: ConversationCapture, output: OutputStream) =
    renderConversationPdf(context, capture) { it.writeTo(output) }

private suspend fun renderConversationPdf(context: Context, capture: ConversationCapture, save: (PdfDocument) -> Unit) {
    val job = currentCoroutineContext()
    val locale = ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.getDefault()
    val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
    val width = 595
    val height = 842
    val margin = 48
    val innerWidth = width - 2 * margin
    val normal = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 12f; textLocale = locale }
    val heading = TextPaint(normal).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 13f }
    val title = TextPaint(heading).apply { textSize = 20f }
    val pdf = PdfDocument()
    try {
        var page: PdfDocument.Page? = null
        var number = 0
        var cursor = height
        fun nextPage(): PdfDocument.Page {
            page?.let(pdf::finishPage)
            val next = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, ++number).create())
            page = next
            cursor = margin
            return next
        }
        fun draw(text: String, paint: TextPaint, keepWithNext: Boolean = false) {
            if (text.isEmpty()) return
            job.ensureActive()
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, innerWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(2f, 1f).build()
            var firstLine = 0
            while (firstLine < layout.lineCount) {
                job.ensureActive()
                val reserve = if (keepWithNext && firstLine == 0 && layout.height + 18 < height - margin * 2) 18 else 0
                if (page == null || cursor + layout.getLineBottom(firstLine) - layout.getLineTop(firstLine) + reserve > height - margin) nextPage()
                val top = layout.getLineTop(firstLine)
                val available = height - margin - cursor
                var last = firstLine + 1
                while (last < layout.lineCount && layout.getLineBottom(last) - top <= available) last++
                val bottom = layout.getLineTop(last)
                val canvas = requireNotNull(page).canvas
                val save = canvas.save()
                canvas.clipRect(margin, cursor, width - margin, cursor + bottom - top)
                canvas.translate(margin.toFloat(), (cursor - top).toFloat())
                layout.draw(canvas)
                canvas.restoreToCount(save)
                cursor += bottom - top + 5
                firstLine = last
            }
        }
        try {
            draw(capture.title, title, keepWithNext = true)
            if (capture.partial) draw(context.getString(R.string.chat_audit_export_partial), normal)
            for (item in capture.items) {
                val label = context.getString(conversationRoleLabel(item.role))
                draw(item.time?.let { "$label · ${time.format(Date(it))}" } ?: label, heading, keepWithNext = true)
                for (block in item.blocks) {
                    val image = block.media
                    if (image != null) draw(context.getString(R.string.chat_audit_media_dimensions,
                        image.name ?: context.getString(R.string.chat_audit_image), image.width, image.height, image.bytes), normal)
                    if (block.kind == BlockKind.TOOL || block.kind == BlockKind.REASONING) draw(
                        block.name.ifBlank { context.getString(if (block.kind == BlockKind.REASONING)
                            R.string.conversation_message_reasoning else R.string.conversation_message_role_tool) }, normal)
                    draw(block.text, normal)
                }
                cursor += 8
            }
        } finally {
            page?.let(pdf::finishPage)
        }
        job.ensureActive()
        save(pdf)
    } finally { pdf.close() }
}
