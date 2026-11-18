package cn.edu.ustc.tokenworks

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionDirectoryDeletionTest {
    @Test fun archiveMembershipIdleRootAndExactOwnerActionsAreRequired() {
        val archived = listOf("root", "child", "running", "other")
        val owner = access("owner", binding("session", "root", "session/follow", "workspace/unarchiveSession"))
        assertTrue(directoryDeletionEligible(row("root"), archived, owner))
        assertFalse(directoryDeletionEligible(row("other"), archived, owner))
        assertFalse(directoryDeletionEligible(row("root"), emptyList(), owner))
        val lan = SessionDirectoryAccess(true, null)
        assertTrue(directoryDeletionEligible(row("root"), archived, lan))
        assertFalse(directoryDeletionEligible(row("child", parent = "root"), archived, lan))
        assertFalse(directoryDeletionEligible(row("running", running = true), archived, lan))
        assertFalse(directoryDeletionEligible(row("other"), archived, access("owner",
            binding("session", "other", "session/follow"), binding("workspace", "other", "workspace/unarchiveSession"))))
        assertFalse(directoryDeletionEligible(row("root"), archived, access("owner",
            binding("session", "root", "workspace/unarchiveSession"))))
        val operator = access("operator", binding("session", "root", "session/follow", "workspace/unarchiveSession"))
        assertTrue(operator.write("workspace/unarchiveSession", "root"))
        assertFalse(directoryDeletionEligible(row("root"), archived, operator))
    }

    @Test fun probesExactSelectionAndRefusesOversizeRatherThanTruncating() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val native: suspend (String, JsonObject) -> JsonValue = { endpoint, args ->
            calls += endpoint to target(args)
            available(true)
        }
        val max = (1..16).map { "selected-$it" }.toSet()
        assertTrue(probeDirectoryDeletion(max, { true }, { true }, native))
        assertEquals(max.map { "sessionDeletion/status" to it }, calls)
        calls.clear()
        assertFalse(probeDirectoryDeletion(max + "seventeenth", { true }, { true }, native))
        assertFalse(probeDirectoryDeletion(emptySet(), { true }, { true }, native))
        assertFalse(probeDirectoryDeletion(setOf("denied"), { true }, { false }, native))
        assertTrue(calls.isEmpty())
    }

    @Test fun unavailableMissingMalformedAndStaleProbesCannotEnableDeletion() = runBlocking {
        val selected = linkedSetOf("first", "second")
        var calls = 0
        assertFalse(probeDirectoryDeletion(selected, { true }, { true }) { _, args ->
            calls++
            available(target(args) != "first")
        })
        assertEquals(1, calls)
        assertFalse(probeDirectoryDeletion(selected, { true }, { true }) { _, _ -> throw DshFailure("gateway/method-unavailable") })
        assertFalse(probeDirectoryDeletion(selected, { true }, { true }) { _, _ -> jsonObject("available" to "true".json()) })
        var current = true
        calls = 0
        assertFalse(probeDirectoryDeletion(selected, { current }, { true }) { _, _ ->
            calls++
            current = false
            available(true)
        })
        assertEquals(1, calls)
    }

    @Test fun writeRechecksLiveAccessAfterStatusAndStopsAtUncertainWithoutReplay() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val denied = mutableSetOf<String>()
        val outcome = runDirectoryDeletion(linkedSetOf("safe", "lost-access", "before-write-refusal", "unknown", "not-reached"),
            { true }, { it !in denied }) { endpoint, args ->
            val id = target(args)
            calls += endpoint to id
            if (endpoint == "sessionDeletion/status") {
                if (id == "lost-access") denied += id
                available(true)
            } else when (id) {
                "before-write-refusal" -> throw DshFailure("session-deletion/unavailable")
                "unknown" -> throw DshFailure("session-deletion/outcome-unknown")
                else -> deleted(true)
            }
        }
        assertEquals(DirectoryDeletionOutcome(setOf("safe"), uncertain = true), outcome)
        assertEquals(listOf("sessionDeletion/status" to "safe", "sessionDeletion/delete" to "safe",
            "sessionDeletion/status" to "lost-access", "sessionDeletion/status" to "before-write-refusal",
            "sessionDeletion/delete" to "before-write-refusal", "sessionDeletion/status" to "unknown",
            "sessionDeletion/delete" to "unknown"), calls)
    }

    @Test fun aFalseOrMalformedAcknowledgementIsNotADeletionAndStopsTheBatch() = runBlocking {
        for (bad in listOf(deleted(false), JsonNull, jsonObject("deleted" to JsonBoolean(true), "extra" to JsonBoolean(true)))) {
            val calls = mutableListOf<Pair<String, String>>()
            val outcome = runDirectoryDeletion(linkedSetOf("first", "second"), { true }, { true }) { endpoint, args ->
                calls += endpoint to target(args)
                if (endpoint == "sessionDeletion/status") available(true) else bad
            }
            assertEquals(DirectoryDeletionOutcome(emptySet(), uncertain = true), outcome)
            assertEquals(listOf("sessionDeletion/status" to "first", "sessionDeletion/delete" to "first"), calls)
        }
    }

    @Test fun routeOrLifecycleInvalidationAfterTheStatusPreventsTheWrite() = runBlocking {
        var current = true
        val calls = mutableListOf<String>()
        val outcome = runDirectoryDeletion(setOf("selected"), { current }, { true }) { endpoint, _ ->
            calls += endpoint
            current = false
            available(true)
        }
        assertEquals(DirectoryDeletionOutcome(emptySet()), outcome)
        assertEquals(listOf("sessionDeletion/status"), calls)
    }

    private fun available(value: Boolean) = jsonObject("available" to JsonBoolean(value))
    private fun deleted(value: Boolean) = jsonObject("deleted" to JsonBoolean(value))
    private fun target(args: JsonObject): String { args.exactKeys("sessionId"); return args.string("sessionId") }

    private fun row(id: String, parent: String? = null, running: Boolean = false) = SessionDirectoryEntry.from(DshSessionListResultItemsItem(
        sessionId = id, agentAvailable = true, updatedAt = 1.0, running = running, blank = false,
        parentSessionId = parent?.let { DshOptional.Value(it) } ?: DshOptional.Missing,
        origin = DshOptional.Value(if (parent == null) "root" else "subagent"),
    ))

    private fun binding(kind: String, id: String, vararg actions: String) = jsonObject(
        "resource" to jsonObject("kind" to kind.json(), "id" to id.json()), "actions" to JsonArray(actions.map(String::json)),
    )

    private fun access(role: String, vararg bindings: JsonObject) = SessionDirectoryAccess(false, RuntimeDirectoryEntry.parse(jsonObject(
        "id" to "44444444-4444-4444-8444-444444444444".json(), "host_id" to "55555555-5555-4555-8555-555555555555".json(),
        "access" to jsonObject("role" to role.json(), "bindings" to JsonArray(bindings.toList())),
    )))
}
