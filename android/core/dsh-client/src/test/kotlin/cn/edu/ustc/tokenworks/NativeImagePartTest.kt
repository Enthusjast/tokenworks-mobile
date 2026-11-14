package cn.edu.ustc.tokenworks

import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

// Real 2x2 PNG from the captured native session/prompt fixture.
internal const val NATIVE_OUTBOX_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAACXBIWXMAAAPoAAAD6AG1e1JrAAAAEUlEQVQImWOosJGrsJFjgFAAGG4DSSYQepwAAAAASUVORK5CYII="

/** Adds a valid, CRC-authenticated ancillary PNG chunk to exercise exact base64 byte boundaries. */
internal fun nativeOutboxImage(encodedBytes: Int = NATIVE_OUTBOX_PNG.length, name: String? = "fixture.png"): NativeImagePart {
    if (encodedBytes == NATIVE_OUTBOX_PNG.length) return NativeImagePart("image/png", NATIVE_OUTBOX_PNG, name)
    require(encodedBytes % 4 == 0)
    val original = Base64.getDecoder().decode(NATIVE_OUTBOX_PNG)
    val targetBytes = encodedBytes / 4 * 3
    val padding = ByteArray(targetBytes - original.size - 12)
    val type = "npAd".toByteArray(Charsets.US_ASCII)
    val crc = CRC32().apply { update(type); update(padding) }
    val bytes = ByteBuffer.allocate(targetBytes).apply {
        put(original, 0, original.size - 12)
        putInt(padding.size); put(type); put(padding); putInt(crc.value.toInt())
        put(original, original.size - 12, 12)
    }.array()
    return NativeImagePart("image/png", Base64.getEncoder().encodeToString(bytes), name)
}

class NativeImagePartTest {
    private val requestId = "00000000-0000-4000-8000-000000000002"

    @Test fun realImageUsesTheGeneratedCodecWithoutRenamingOrReencoding() {
        val image = nativeOutboxImage(name = "原样🙂.png")
        val expected = DshSessionPromptInputRequestContentItemCase2("image", image.mediaType, NATIVE_OUTBOX_PNG,
            DshOptional.Value("原样🙂.png"))
        assertEquals(expected, DshSessionPromptInputRequestContentItemCase2.codec.decode(image.promptPart()))
        assertEquals(NATIVE_OUTBOX_PNG.length, image.encodedBytes)
        assertFalse(nativeOutboxImage(name = null).promptPart().fields.containsKey("name"))
        val gif = NativeImagePart("image/gif", "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        assertEquals(gif.data, DshSessionPromptInputRequestContentItemCase2.codec.decode(gif.promptPart()).data)
    }

    @Test fun invalidMimeNamesAndNoncanonicalBase64FailWithoutEchoingThePayload() {
        for (mime in listOf("image/svg+xml", "image/PNG", "text/plain", "image/png; charset=utf-8")) {
            assertThrows(IllegalArgumentException::class.java) { NativeImagePart(mime, NATIVE_OUTBOX_PNG) }
        }
        for (data in listOf("", "Zg", "Zg=", "Zg===", "Zg==\n", "Zg-_", "=g==", "Zh==", "Zm9=", "====", "🙂🙂")) {
            val error = assertThrows(IllegalArgumentException::class.java) { NativeImagePart("image/png", data) }
            assertFalse(error.message.orEmpty().contains("data="))
        }
        for (name in listOf("", " ", ".", "..", "../secret.png", "x\\secret.png", "x\u0000.png", "x\u0085.png",
            "x\u202e.png", "x\u2066.png", "中".repeat(NativeImagePart.MAX_NAME_BYTES / 3 + 1), "\uD800")) {
            assertThrows(IllegalArgumentException::class.java) { nativeOutboxImage(name = name) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeImagePart("image/png", "A".repeat(NativeImagePart.MAX_BATCH_ENCODED_BYTES + 4))
        }
    }

    @Test fun imageAndPromptStringRepresentationsNeverContainPayloadOrNames() {
        val image = nativeOutboxImage(name = "private-image-name.png")
        val entry = PromptOutboxEntry(requestId, DshCommandRoute("owner", "host", "runtime", "session"),
            "private text", "queue", receipts = listOf("private receipt"), images = listOf(image))
        for (rendered in listOf(image.toString(), entry.toString(), entry.copy().toString(), listOf(entry).toString())) {
            for (secret in listOf(image.data, image.name!!, "private text", "private receipt")) assertFalse(rendered.contains(secret))
        }
    }

    @Test fun fileArgumentsAppendExactImagesAndKeepTextAndReceiptBudgetsIndependent() {
        val image = nativeOutboxImage(NativeImagePart.MAX_BATCH_ENCODED_BYTES)
        val attachment = UploadedAttachment("session", "opaque receipt", "opaque attachment", "document", 1)
        val text = "x".repeat(DshCommandOutbox.MAX_TEXT_BYTES - attachment.receiptId.toByteArray().size)
        val arguments = filePromptArguments("session", requestId, text, listOf(attachment), "steer", listOf(image))
        val request = DshMethods.SessionPrompt.input.decode(arguments).request
        assertEquals("session", request.sessionId)
        assertEquals(requestId, request.requestId)
        assertEquals("steer", request.mode)
        val content = arguments.obj("request").array("content")
        assertEquals(listOf("text", "file", "image"), content.map { (it as JsonObject).string("type") })
        val codec = DshSessionPromptInputRequestContentItemCase2.codec
        assertEquals(codec.decode(image.promptPart()), codec.decode(content.last()))
        assertEquals(codec.decode(image.promptPart()), codec.decode(
            filePromptArguments("session", requestId, "", emptyList(), images = listOf(image))
                .obj("request").array("content").single()))
        assertThrows(IllegalArgumentException::class.java) {
            filePromptArguments("session", requestId, text + "x", listOf(attachment), images = listOf(image))
        }
        assertThrows(IllegalArgumentException::class.java) {
            filePromptArguments("other-session", requestId, "", listOf(attachment), images = listOf(image))
        }
        assertThrows(IllegalArgumentException::class.java) {
            filePromptArguments("session", requestId, "", emptyList(), images = List(5) { nativeOutboxImage() })
        }
        assertThrows(IllegalArgumentException::class.java) {
            filePromptArguments("session", requestId, "", emptyList(), images = listOf(image, nativeOutboxImage()))
        }
    }
}
