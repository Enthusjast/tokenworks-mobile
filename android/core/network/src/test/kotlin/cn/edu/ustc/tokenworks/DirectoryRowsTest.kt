package cn.edu.ustc.tokenworks

import org.junit.Assert.*
import org.junit.Test

class DirectoryRowsTest {
    private val runtimeId = "44444444-4444-4444-8444-444444444444"
    private val hostId = "55555555-5555-4555-8555-555555555555"
    private val sessionId = "66666666-6666-4666-8666-666666666666"
    private val otherSessionId = "77777777-7777-4777-8777-777777777777"

    private fun binding(kind: String, id: String?, vararg actions: String): JsonObject = jsonObject(
        "actions" to JsonArray(actions.map { it.json() }),
        "resource" to if (id == null) jsonObject("kind" to kind.json())
        else jsonObject("kind" to kind.json(), "id" to id.json()),
    )

    private fun runtime(vararg bindings: JsonObject): RuntimeDirectoryEntry = RuntimeDirectoryEntry.parse(
        jsonObject(
            "id" to runtimeId.json(), "host_id" to hostId.json(),
            "access" to jsonObject(
                "role" to "operator".json(), "grant_version" to 1L.json(),
                "bindings" to JsonArray(bindings.toList()),
            ),
        ),
    )

    @Test fun kindWideSessionGrantCoversEverySessionOfItsRuntime() {
        val entry = runtime(binding("session", null, "session/list", "session/follow", "session/prompt"))
        assertTrue(entry.canList())
        assertTrue(entry.canFollow(sessionId))
        assertTrue(entry.canPrompt(otherSessionId))
        assertFalse(entry.canRename(sessionId))
    }

    @Test fun namedSessionAndWorkspaceGrantsCoverOnlyTheirOwnResource() {
        val entry = runtime(
            binding("session", sessionId, "session/follow", "workspace/archiveSession"),
            binding("workspace", otherSessionId, "workspace/rename"),
        )
        assertTrue(entry.canFollow(sessionId))
        assertFalse(entry.canFollow(otherSessionId))
        assertTrue(entry.canArchive(sessionId))
        assertFalse(entry.allows("workspace/rename", sessionId))
        assertTrue(entry.allows("workspace/rename", otherSessionId))
    }

    @Test fun globalGrantCarriesNoResourceAndKindWideGrantsCarryAnyNamedResource() {
        assertEquals(NativeResource("global", null), NativeResource.parse(jsonObject("kind" to "global".json())))
        assertEquals(NativeResource("session", null), NativeResource.parse(jsonObject("kind" to "session".json())))
        assertEquals(NativeResource("workspace", sessionId), NativeResource.parse(jsonObject("kind" to "workspace".json(), "id" to sessionId.json())))
        assertThrows(ProtocolException::class.java) {
            NativeResource.parse(jsonObject("kind" to "global".json(), "id" to sessionId.json()))
        }
        assertThrows(ProtocolException::class.java) {
            NativeResource.parse(jsonObject("kind" to "runtime".json()))
        }
    }
}
