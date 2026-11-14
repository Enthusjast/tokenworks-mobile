package cn.edu.ustc.tokenworks

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HostManagementApiTest {
    private val installation = "11111111-1111-4111-8111-111111111111"
    private val runtime = "22222222-2222-4222-8222-222222222222"
    private val user = "33333333-3333-4333-8333-333333333333"
    private fun response(data: JsonObject) = jsonObject("ok" to JsonBoolean(true), "data" to data)

    @Test fun revocationsUseExistingEmptyBodyDeleteRoutes() = runBlocking {
        val sent = mutableListOf<Pair<String, String>>()
        val api = HostManagementApi { method, path -> sent += method to path; response(jsonObject("revoked" to JsonBoolean(true))) }
        api.revoke(ManagementTarget.Device(user))
        api.revoke(ManagementTarget.Installation(installation))
        api.revoke(ManagementTarget.Profile(installation, runtime))
        api.revoke(ManagementTarget.Grant(runtime, user))
        assertEquals(listOf("/v1/devices/$user", "/v1/installations/$installation", "/v1/installations/$installation/runtimes/$runtime", "/v1/runtimes/$runtime/grants/$user"), sent.map { it.second })
        assertTrue(sent.all { it.first == "DELETE" })
    }
    @Test fun ambiguousMutationDoesNotBecomeSuccessOrImplicitRetry() = runBlocking {
        var calls = 0
        val api = HostManagementApi { _, _ -> calls++; throw RemoteRequestException("network.unavailable") }
        assertTrue(runCatching { api.revoke(ManagementTarget.Installation(installation)) }.isFailure)
        assertEquals(1, calls)
        val rejected = HostManagementApi { _, _ -> response(jsonObject("revoked" to JsonBoolean(false))) }
        assertTrue(runCatching { rejected.revoke(ManagementTarget.Installation(installation)) }.isFailure)
    }
    @Test fun paginationEncodesCursorAndPreservesUnknownState() = runBlocking {
        var sent = ""
        val api = HostManagementApi { _, path -> sent = path; response(jsonObject("items" to JsonArray(listOf(jsonObject(
            "device_id" to user.json(), "name" to "Phone".json(), "state" to "operator-defined".json(), "is_current" to JsonBoolean(true)
        ))), "next_cursor" to JsonNull)) }
        val page = api.devices("a+b/c")
        assertEquals("/v1/devices?limit=50&cursor=a%2Bb%2Fc", sent)
        assertEquals("operator-defined", page.items.single().state)
        assertTrue(page.items.single().current)
        assertNull(page.nextCursor)
    }
    @Test fun mismatchedProfileOrGrantResourceCannotPopulateConsumerState() = runBlocking {
        val api = HostManagementApi { _, _ -> response(jsonObject("installation_id" to user.json(), "runtime_id" to user.json(), "items" to JsonArray(emptyList()), "next_cursor" to JsonNull)) }
        assertTrue(runCatching { api.profiles(installation) }.isFailure)
        assertTrue(runCatching { api.grants(runtime) }.isFailure)
    }
    @Test fun repeatingCursorAndOversizedPageAreRejected() = runBlocking {
        val repeated = HostManagementApi { _, _ -> response(jsonObject("items" to JsonArray(emptyList()), "next_cursor" to "again".json())) }
        assertTrue(runCatching { repeated.devices("again") }.isFailure)
        val oversized = HostManagementApi { _, _ -> response(jsonObject("items" to JsonArray(List(51) { jsonObject() }), "next_cursor" to JsonNull)) }
        assertTrue(runCatching { oversized.devices() }.isFailure)
    }
}
