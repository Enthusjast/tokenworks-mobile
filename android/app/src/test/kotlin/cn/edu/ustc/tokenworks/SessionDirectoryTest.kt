package cn.edu.ustc.tokenworks

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionDirectoryTest {
    @Test fun metadataMatchesDoNotGrantNativeContentAccess() {
        val rows = listOf(row("a", "Build status"), row("b", "Other"), row("c", "Cold build", cold = true))
        val hits = DshSessionSearchResult(listOf(
            DshSessionSearchResultItemsItem("a", "denied snippet"), DshSessionSearchResultItemsItem("b", "Actual BUILD message"),
            DshSessionSearchResultItemsItem("unknown", "not listed on this runtime"),
        ), false)
        val results = sessionDirectorySearch(rows, "build", hits) { it != "a" }
        assertEquals(mapOf("a" to null, "b" to "Actual BUILD message", "c" to null), results)
        assertEquals("unavailable", rows[2].state)
        assertNull(rows[2].workspaceId)
    }

    @Test fun archivedMembershipFollowsSubagentAncestryButNotForksOrCycles() {
        val rows = listOf(row("root"), row("child", parent = "root"), row("grandchild", parent = "child"),
            row("fork", parent = "root", origin = "fork"), row("loop-a", parent = "loop-b"), row("loop-b", parent = "loop-a"))
        assertEquals(setOf("root", "child", "grandchild"), directoryArchivedRows(rows, setOf("root")))
        assertTrue(directoryArchivedRows(rows, emptySet()).isEmpty())
    }

    @Test fun pinPromotesMatchedChildAndRoomForUnpinnedParentsRemains() {
        val rows = listOf(row("parent", updated = 2.0), row("child", parent = "parent", updated = 10.0), row("recent", updated = 20.0))
        val pins = setOf("child", "unknown")
        fun ids(groups: Map<DirectorySection, List<DirectoryPlacement>>, section: DirectorySection) =
            groups.getValue(section).map { it.entry.id to it.depth }
        val grouped = sessionDirectoryGroups(rows, 10, pins) { true }
        assertEquals(listOf("child" to 0), ids(grouped, DirectorySection.PINNED))
        assertEquals(listOf("recent" to 0), ids(grouped, DirectorySection.RECENT))
        assertEquals(listOf("parent" to 0), ids(grouped, DirectorySection.EARLIER))
        val filtered = sessionDirectoryGroups(rows, 10, emptySet()) { it.id == "child" }
        assertEquals(listOf("parent" to 0, "child" to 1), ids(filtered, DirectorySection.EARLIER))
        assertNotEquals(directoryPinFile("owner", "one"), directoryPinFile("owner", "two"))
        assertNotEquals(directoryPinFile("owner", "one"), directoryPinFile("other", "one"))
    }

    @Test fun scopeUsesKindActionIdAndRoleNotOverlappingStrings() {
        val access = access("operator", binding("workspace", "same", "session/rename", "session/page"),
            binding("session", "same", "session/search", "workspace/archiveSession"))
        assertTrue(access.read("session/search"))
        assertTrue(access.read("session/search", "same"))
        assertFalse(access.read("session/search", "other"))
        assertFalse(access.read("session/page", "same"))
        assertFalse(access.write("session/rename", "same"))
        assertTrue(access.write("workspace/archiveSession", "same"))
        assertFalse(access.write("workspace/archiveSession", "other"))
        assertFalse(access.write("workspace/unarchiveSession", "same"))
        assertFalse(access("viewer", binding("session", "same", "workspace/archiveSession")).write("workspace/archiveSession", "same"))
    }

    @Test fun latestPreviewOmitsReasoningToolPayloadsAndInternalMessages() {
        val prompt = jsonObject("content" to JsonArray(listOf(part("text", "real first line\nmore"))))
        val internal = prompt.with("source" to jsonObject("kind" to "system-prompt".json())).with("content" to JsonArray(listOf(part("text", "hidden"))))
        val position = jsonObject("turn" to 3L.json(), "step" to 2L.json())
        val events = listOf(
            record(0, "user/message", prompt), record(1, "user/message", internal),
            record(2, "step/start", position), record(3, "assistant/message", position.with("message" to
                jsonObject("content" to JsonArray(listOf(part("reasoning", "private chain"), part("tool-call", "tool args")))))),
        )
        assertEquals(DirectoryPreview("real first line", true, DirectoryTurn(3, 2)), projectDirectoryPreview(DshSessionPageResult(3.0, events, false)))
        val file = jsonObject("content" to JsonArray(listOf(jsonObject("type" to "file".json()))))
        val later = events + record(4, "turn/end", position) + record(5, "user/message", file)
        assertEquals(DirectoryPreview(hasMessage = true), projectDirectoryPreview(DshSessionPageResult(5.0, later, false)))
    }

    @Test fun bulkRechecksEveryTargetAndStopsAfterUnknownOutcome() = runBlocking {
        val dispatched = mutableListOf<String>()
        val result = runDirectoryArchive(linkedSetOf("saved", "blocked", "active", "unknown", "never"), { true }, { it != "blocked" }) {
            dispatched += it
            when (it) {
                "active" -> throw DshFailure("workspace/session-active")
                "unknown" -> throw DshFailure("network.unavailable")
            }
        }
        assertEquals(listOf("saved", "active", "unknown"), dispatched)
        assertEquals(setOf("saved"), result.completed)
        assertEquals(setOf("blocked"), result.blocked)
        assertEquals(setOf("active", "unknown"), result.unconfirmed.keys)
        var current = true
        assertTrue(runDirectoryArchive(setOf("one", "two"), { current }, { true }) {
            assertEquals("one", it)
            current = false
        }.completed.isEmpty())
    }

    @Test fun archiveAndRestoreUseOnlyNativeArchiveMembershipCommands() = runBlocking {
        val requests = mutableListOf<Pair<String, JsonObject>>()
        val wire = object : DshConnection {
            override suspend fun native(endpoint: String, args: JsonObject): JsonValue {
                requests += endpoint to args
                return jsonObject("archivedSessionIds" to JsonArray(if (endpoint == "workspace/archiveSession") listOf("id".json()) else emptyList()))
            }
            override suspend fun openMux(): DshMux = error("Only unary calls are expected")
            override fun close() = Unit
        }
        sendDirectoryArchive(wire, "id", true)
        sendDirectoryArchive(wire, "id", false)
        assertEquals(listOf("workspace/archiveSession", "workspace/unarchiveSession"), requests.map { it.first })
        assertEquals(JsonBoolean(false), requests[0].second.obj("request")["stopActivity"])
    }

    private fun record(seq: Int, type: String, data: JsonObject) = DshSessionPageResultRecordsItem("event",
        DshSessionPageResultRecordsItemEvent(type, seq.toDouble(), seq.toDouble(), data))
    private fun part(type: String, text: String) = jsonObject("type" to type.json(), "text" to text.json())
    private fun row(id: String, title: String = id, parent: String? = null, updated: Double = 1.0, cold: Boolean = false,
        origin: String = "subagent") = SessionDirectoryEntry.from(DshSessionListResultItemsItem(
        sessionId = id, agentAvailable = !cold, running = false, blank = false, updatedAt = updated,
        parentSessionId = parent?.let { DshOptional.Value(it) } ?: DshOptional.Missing,
        origin = DshOptional.Value(origin),
        projections = DshOptional.Value(DshSessionListResultItemsItemProjections("cached", 1.0,
            DshSessionListResultItemsItemProjectionsValues(title = DshOptional.Value(title), workingDirectory = DshOptional.Value("/work")))),
    ))

    private fun binding(kind: String, id: String, vararg actions: String) = jsonObject(
        "resource" to jsonObject("kind" to kind.json(), "id" to id.json()), "actions" to JsonArray(actions.map(String::json)),
    )
    private fun access(role: String, vararg bindings: JsonObject) = SessionDirectoryAccess(false, RuntimeDirectoryEntry.parse(jsonObject(
        "id" to "44444444-4444-4444-8444-444444444444".json(), "host_id" to "55555555-5555-4555-8555-555555555555".json(),
        "access" to jsonObject("role" to role.json(), "bindings" to JsonArray(bindings.toList())),
    )))
}
