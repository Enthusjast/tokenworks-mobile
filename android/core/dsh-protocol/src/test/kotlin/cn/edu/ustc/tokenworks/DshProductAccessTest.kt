package cn.edu.ustc.tokenworks

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DshProductAccessTest {
    private val canonical = JsonDocument.parse(File(System.getProperty("contracts.root"), "typert/product-access.json").readBytes()).root

    @Test fun generatedPolicyMatchesEveryCanonicalActionCapabilityResourceAndSelectedNativeMethod() {
        assertEquals(canonical.string("dshVersion"), DshProductAccess.VERSION)
        assertEquals(canonical.string("dshVersionRange"), DshProductAccess.VERSION_RANGE)
        assertTrue(DshProductAccess.isSupportedDshVersion(DshProductAccess.VERSION))
        val source = canonical.obj("actions")
        assertEquals(source.fields.keys, DshProductAccess.actions.keys)
        assertEquals(DshNativeRegistry.methods.keys, DshProductAccess.actions.filterValues { it.selected }.keys)
        assertEquals(40, DshProductAccess.actions.count { it.value.selected })
        for ((action, value) in source.fields) {
            val entry = value as JsonObject
            val policy = DshProductAccess.actions.getValue(action)
            val resources = entry.array("resources").map { (it as JsonString).value }.toSet()
            assertEquals(entry.string("capability"), policy.capability)
            assertEquals((entry.fields["eventDelivery"] as? JsonString)?.value, policy.eventDelivery)
            assertEquals(resources, policy.resources)
            assertEquals((entry["globalCall"] as JsonBoolean).value, DshProductAccess.allowsGlobal(action))
            for (kind in listOf("global", "session", "workspace", "terminal", "unknown")) {
                assertEquals("$action:$kind", kind in resources, DshProductAccess.allowsResource(action, kind))
                assertEquals(kind in resources, DshProductAccess.ownerBindings.any { it.resource.kind == kind && action in it.actions })
            }
        }
    }

    @Test fun nativeSessionEventsDoNotBecomeGlobalAndRetainedLogCapabilityDoesNotClaimForwarding() {
        for (action in listOf("event:api-session/status", "event:api-session/activity")) {
            assertEquals(setOf("session"), DshProductAccess.actions.getValue(action).resources)
            assertEquals("emit", DshProductAccess.actions.getValue(action).eventDelivery)
            assertFalse(DshProductAccess.allowsResource(action, "global"))
        }
        assertEquals("not-forwarded", DshProductAccess.actions.getValue("event:approval/decided").eventDelivery)
        assertEquals("owner-waterfall", DshProductAccess.actions.getValue("event:approval/request").eventDelivery)
        assertEquals("owner-waterfall", DshProductAccess.actions.getValue("event:user-questions/request").eventDelivery)
    }

    @Test fun terminalAttachAndAllTerminalCallsRequireSessionRatherThanGlobalOrWorkspace() {
        for (action in DshProductAccess.actions.keys.filter { it.startsWith("terminal/") }) {
            assertEquals(setOf("session"), DshProductAccess.actions.getValue(action).resources)
            assertFalse(DshProductAccess.allowsGlobal(action))
            assertTrue(DshProductAccess.isBinding(DshProductBinding(DshProductResource("session", "session-1"), setOf(action))))
            assertFalse(DshProductAccess.isBinding(DshProductBinding(DshProductResource("global"), setOf(action))))
        }
        assertTrue(DshProductAccess.allowsResource("workspace/archiveSession", "session"))
        assertFalse(DshProductAccess.allowsResource("workspace/archiveSession", "workspace"))
        assertTrue(DshProductAccess.allowsGlobal("session/list"))
        assertFalse(DshProductAccess.allowsGlobal("session/prompt"))
    }

    @Test fun unknownActionsCrossKindBindingsAndMalformedResourcesAreDenied() {
        for (resource in listOf(DshProductResource("global", "id"), DshProductResource("session", ""), DshProductResource("workspace", "x".repeat(2049)), DshProductResource("terminal"))) {
            assertFalse(DshProductAccess.isResource(resource))
        }
        for (kind in listOf("global", "session", "workspace")) assertTrue(DshProductAccess.isResource(DshProductResource(kind)))
        assertFalse(DshProductAccess.allowsResource("unknown/action", "global"))
        assertFalse(DshProductAccess.allowsGlobal("terminal/unknown"))
        assertFalse(DshProductAccess.isBinding(DshProductBinding(DshProductResource("session"), setOf("event:unknown"))))
        assertFalse(DshProductAccess.isBinding(DshProductBinding(DshProductResource("workspace"), setOf("session/prompt"))))
    }

    @Test fun onlyActualOwnerWithCompleteKindWideMatrixMayHaveNativeAnswerAuthority() {
        val bindings = DshProductAccess.ownerBindings
        assertTrue(DshProductAccess.ownerEligible("owner", true, bindings))
        assertFalse(DshProductAccess.ownerEligible("viewer", true, bindings))
        assertFalse(DshProductAccess.ownerEligible("operator", true, bindings))
        assertFalse(DshProductAccess.ownerEligible("owner", false, bindings))
        assertFalse(DshProductAccess.ownerEligible("owner", true, emptyList()))
        for (binding in bindings) {
            for (action in binding.actions) {
                val partial = bindings.map { if (it.resource.kind == binding.resource.kind) it.copy(actions = it.actions - action) else it }
                assertFalse("${binding.resource.kind}:$action", DshProductAccess.ownerEligible("owner", true, partial))
            }
        }
        val concrete = bindings.map { if (it.resource.kind == "global") it else it.copy(resource = it.resource.copy(id = "only-one")) }
        assertFalse(DshProductAccess.ownerEligible("owner", true, concrete))
        assertEquals(DshProductInteractions("answer", true), DshProductAccess.interactions("owner", true, bindings, true))
        assertEquals(DshProductInteractions("answer", false), DshProductAccess.interactions("owner", true, bindings, false))
        assertEquals(DshProductInteractions("answer", false), DshProductAccess.interactions("viewer", false, bindings, true))
    }
}
