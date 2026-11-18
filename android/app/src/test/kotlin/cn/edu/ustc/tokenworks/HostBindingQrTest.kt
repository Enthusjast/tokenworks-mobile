package cn.edu.ustc.tokenworks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostBindingQrTest {
    private val id = "8f75cb88-4912-47ce-8ffc-a3bc80f4cab9"
    private val code = "A".repeat(43)
    private val payload = """{"type":"tokenworks.binding.v1","id":"$id","code":"$code"}"""

    @Test fun acceptsOnlyTypedExactHandoffWithValidOneTimeFields() {
        val binding = checkNotNull(HostBindingQr.parse(payload))
        assertEquals(id, binding.id)
        assertEquals(code, binding.code)
        assertNull(HostBindingQr.parse(payload.replace(".v1", ".v2")))
        assertNull(HostBindingQr.parse(payload.replace(id, "https://invalid.example")))
        assertNull(HostBindingQr.parse(payload.replace(code, "invalid")))
        assertNull(HostBindingQr.parse(payload.dropLast(1) + """, "id":"$id"}"""))
        assertNull(HostBindingQr.parse(payload.dropLast(1) + """, "host":"https://invalid.example"}"""))
    }
}
