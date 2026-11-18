package cn.edu.ustc.tokenworks

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DshWorkspaceClientTest {
    @Test fun followUsesNativeBaselineIncrementsAndAuthoritativeReset() = runTest {
        val transport = InteractionTestTransport()
        val client = DshWorkspaceClient(transport)
        val observed = backgroundScope.async { client.follow().take(8).toList() }
        runCurrent()
        val mux = transport.muxes.single()
        assertNativeJson(WireJson.parse("""{"type":"open","streamId":"1","endpoint":"workspace/follow","payload":{"args":{}}}"""),
            transport.frames.single())

        mux.workspaceItem("""{"type":"baseline","value":{"items":[${workspace("east", "East")},${workspace("west", "West")}],"archivedSessionIds":["old"],"pinnedSessionIds":["one"]}}""")
        mux.workspaceItem("""{"type":"upsert","workspace":${workspace("east", "Renamed", "new-session")}}""")
        mux.workspaceItem("""{"type":"upsert","workspace":${workspace("north", "North")}}""")
        mux.workspaceItem("""{"type":"order","workspaceIds":["north","east","west"]}""")
        mux.workspaceItem("""{"type":"remove","workspaceId":"west"}""")
        mux.workspaceItem("""{"type":"archived","archivedSessionIds":["old","new-session"]}""")
        mux.workspaceItem("""{"type":"pinned","pinnedSessionIds":["third","one"]}""")
        mux.workspaceItem("""{"type":"replace","value":{"items":[${workspace("west", "West again")}],"archivedSessionIds":[],"pinnedSessionIds":["new-pin"]}}""")
        runCurrent()

        val states = observed.await()
        assertEquals(listOf("east", "west"), states[0].workspaces.map(DshWorkspace::id))
        assertEquals(listOf("old"), states[0].archivedSessionIds)
        assertEquals(listOf("one"), states[0].pinnedSessionIds)
        assertEquals(DshWorkspace("east", "/work/east", "Renamed", listOf("new-session")), states[1].workspaces.first())
        assertEquals(listOf("east", "west", "north"), states[2].workspaces.map(DshWorkspace::id))
        assertEquals(listOf("north", "east", "west"), states[3].workspaces.map(DshWorkspace::id))
        assertEquals(listOf("north", "east"), states[4].workspaces.map(DshWorkspace::id))
        assertEquals(listOf("old", "new-session"), states[5].archivedSessionIds)
        assertEquals(listOf("third", "one"), states[6].pinnedSessionIds)
        assertEquals(DshWorkspaceSnapshot(listOf(DshWorkspace("west", "/work/west", "West again", emptyList())), emptyList(), listOf("new-pin")),
            states.last())
        assertTrue(mux.closed())
        assertEquals("cancel", transport.frames.last().string("type"))
    }

    @Test fun eachSubscriptionRequiresItsOwnBaseline() = runTest {
        val transport = InteractionTestTransport()
        val client = DshWorkspaceClient(transport)
        val first = backgroundScope.async { client.follow().first() }
        runCurrent()
        transport.muxes.single().workspaceItem("""{"type":"baseline","value":{"items":[],"archivedSessionIds":[],"pinnedSessionIds":[]}}""")
        runCurrent()
        assertEquals(DshWorkspaceSnapshot(emptyList(), emptyList(), emptyList()), first.await())
        val next = backgroundScope.async { runCatching { client.follow().first() } }
        runCurrent()
        transport.muxes.last().workspaceItem("""{"type":"pinned","pinnedSessionIds":["stale"]}""")
        runCurrent()
        assertTrue(next.await().exceptionOrNull() is ProtocolException)
        assertTrue(transport.muxes.all(DshMux::closed))
    }

    @Test fun requestsUseNativeNamesAndOmitUnspecifiedAnchorsOrStopActivity() = runTest {
        val transport = InteractionTestTransport()
        val client = DshWorkspaceClient(transport)
        suspend fun request(endpoint: String, args: String, result: String, call: suspend () -> Unit) {
            transport.result = { WireJson.parse(result) }
            call()
            val (actualEndpoint, actualArgs) = transport.posts.removeAt(0)
            assertEquals("workspace/$endpoint", actualEndpoint)
            assertNativeJson(WireJson.parse(args), actualArgs)
            assertTrue(transport.posts.isEmpty())
        }
        val workspaceResult = """{"workspace":${workspace("east", "Renamed")}}"""
        request("create", """{"request":{"path":"/work/east"}}""",
            """{"workspace":${workspace("east", "East")},"created":true}""") { client.create("/work/east") }
        request("rename", """{"request":{"workspaceId":"east","title":"Renamed"}}""", workspaceResult) { client.rename("east", "Renamed") }
        request("delete", """{"request":{"workspaceId":"east"}}""", """{"deleted":true}""") { client.delete("east") }
        request("insertBefore", """{"request":{"workspaceId":"east","beforeWorkspaceId":"west"}}""",
            """{"workspaceIds":["east","west"]}""") { client.insertBefore("east", "west") }
        request("insertBefore", """{"request":{"workspaceId":"east"}}""",
            """{"workspaceIds":["west","east"]}""") { client.insertBefore("east") }
        request("insertSessionBefore", """{"request":{"workspaceId":"east","sessionId":"one","beforeSessionId":"two"}}""",
            workspaceResult) { client.insertSessionBefore("east", "one", "two") }
        request("insertSessionBefore", """{"request":{"workspaceId":"east","sessionId":"one"}}""",
            workspaceResult) { client.insertSessionBefore("east", "one") }
        request("pinSession", """{"request":{"sessionId":"one"}}""", """{"pinnedSessionIds":["one"]}""") { client.pinSession("one") }
        request("unpinSession", """{"request":{"sessionId":"one"}}""", """{"pinnedSessionIds":[]}""") { client.unpinSession("one") }
        request("archiveSession", """{"request":{"sessionId":"one"}}""", """{"archivedSessionIds":["one"]}""") { client.archiveSession("one") }
        request("archiveSession", """{"request":{"sessionId":"one","stopActivity":true}}""",
            """{"archivedSessionIds":["one"]}""") { client.archiveSession("one", stopActivity = true) }
        request("unarchiveSession", """{"request":{"sessionId":"one"}}""", """{"archivedSessionIds":[]}""") { client.unarchiveSession("one") }
    }

    private fun DshMux.workspaceItem(text: String) = event(WireJson.parse(text))

    private fun workspace(id: String, title: String, vararg sessions: String): String = WireJson.encode(jsonObject(
        "workspaceId" to id.json(), "path" to "/work/$id".json(), "title" to title.json(),
        "sessionIds" to JsonArray(sessions.map(String::json)),
        "createdAt" to "2026-11-18T10:00:00Z".json(), "updatedAt" to "2026-11-18T10:00:01Z".json(),
    ))
}
