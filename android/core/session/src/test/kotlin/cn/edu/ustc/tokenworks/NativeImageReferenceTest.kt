package cn.edu.ustc.tokenworks

import org.junit.Assert.*
import org.junit.Test

class NativeImageReferenceTest {
    private fun reference() = NativeImageReference("sha256:${"a".repeat(64)}", "image/png", 123, 8, 6, "图片.png")
    private fun event(content: List<JsonValue>) = jsonObject("seq" to 0L.json(), "time" to 1000L.json(),
        "type" to "user/message".json(), "data" to jsonObject("content" to JsonArray(content)))
    private fun image() = jsonObject("type" to "image".json(), "attachment" to reference().json())

    @Test fun nativeAttachmentParsesWithoutManifestAndKeepsTextAndImageOrder() {
        val record = SessionRecord.parse(event(listOf(jsonObject("type" to "text".json(), "text" to "look".json()), image())))
        assertEquals(listOf(BlockKind.TEXT, BlockKind.IMAGE), record.blocks.map { it.kind })
        assertEquals("look", record.blocks.first().text)
        val image = record.blocks.last().media!!
        assertTrue(reference().same(image))
        assertEquals("图片.png", record.blocks.last().name)
        assertFalse(image.toString().contains("sha256"))
        assertFalse(image.toString().contains("图片"))
        assertTrue(image.same(NativeImageReference.parse(image.json())))
        val noName = NativeImageReference.parse(JsonObject(image.json().fields.filterKeys { it != "name" }))
        assertNull(noName.name)
    }

    @Test fun nativeImageRejectsAuthorityFieldsMalformedIntrinsicMetadataAndLegacySchema() {
        val root = reference().json()
        val invalid = listOf(root.with("attachmentId" to "https://host.invalid/image".json()),
            root.with("attachmentId" to "sha256:${"A".repeat(64)}".json()), root.with("mediaType" to "image/svg+xml".json()),
            root.with("bytes" to 0L.json()), root.with("bytes" to (NativeImageReference.MAX_BYTES + 1).json()),
            root.with("width" to 0L.json()), root.with("height" to 0L.json()), root.with("width" to 16_384L.json(), "height" to 16_384L.json()),
            root.with("width" to JsonNumber(1.5)), root.with("name" to "../secret.png".json()),
            root.with("name" to "a\\b.png".json()), root.with("name" to "a\u202eb.png".json()), root.with("name" to JsonNull))
        for (bad in invalid) assertThrows(ProtocolException::class.java) { NativeImageReference.parse(bad) }
        for (key in listOf("manifest", "receiptId", "url", "source", "authorization", "approval")) {
            assertThrows(Exception::class.java) { NativeImageReference.parse(root.with(key to "private".json())) }
        }
        for (key in listOf("attachmentId", "mediaType", "bytes", "width", "height")) {
            assertThrows(Exception::class.java) { NativeImageReference.parse(JsonObject(root.fields.filterKeys { it != key })) }
        }
        assertThrows(Exception::class.java) {
            SessionRecord.parse(event(listOf(jsonObject("type" to "image".json(), "reference" to root))))
        }
    }

    @Test fun boundedHugeTextNeverReplacesAnImageWithATextPlaceholder() {
        val record = SessionRecord.parse(event(listOf(image(), jsonObject("type" to "text".json(), "text" to "x".repeat(100_000).json()))))
        assertEquals(BlockKind.IMAGE, record.blocks.first().kind)
        assertTrue(reference().same(record.blocks.first().media!!))
        assertTrue(record.blocks.last().text.endsWith("…[已截断]"))
    }
}
