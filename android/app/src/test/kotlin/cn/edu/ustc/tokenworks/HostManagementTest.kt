package cn.edu.ustc.tokenworks

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class HostManagementTest {
    private val runtime = "11111111-1111-4111-8111-111111111111"
    private val host = "22222222-2222-4222-8222-222222222222"
    private val installation = "33333333-3333-4333-8333-333333333333"
    private val other = "44444444-4444-4444-8444-444444444444"
    private fun row(installationId: String?) = RuntimeRow(runtime, host, "", "Workstation", RuntimeDirectoryEntry.parse(
        jsonObject("id" to runtime.json(), "host_id" to host.json()).let { base ->
            if (installationId == null) base else JsonObject(base.fields + ("installation_id" to installationId.json()))
        }))
    private fun lan(id: String = installation) = PairedLanDevice(runtime, id, "https://192.168.1.2:8443", other, 1, 1)

    @Test fun pathsMergeOnlyWithExactAuthoritativeInstallationAndRuntime() {
        val merged = groupHostDirectory(listOf(row(installation)), listOf(lan()), false).single().runtimes.single()
        assertTrue(merged.identity.relayAuthorized)
        assertEquals(HostConnectionPath.entries.toSet(), merged.paths)
        for (relayInstallation in listOf(null, other)) {
            val separate = groupHostDirectory(listOf(row(relayInstallation)), listOf(lan()), false).flatMap { it.runtimes }
            assertEquals(2, separate.size)
            assertEquals(setOf(HostConnectionPath.RELAY), separate.single { it.identity.relayAuthorized }.paths)
            assertEquals(setOf(HostConnectionPath.AUTO, HostConnectionPath.LAN, HostConnectionPath.P2P, HostConnectionPath.TURN), separate.single { !it.identity.relayAuthorized }.paths)
        }
    }
    @Test fun lostPathNeverSelectsAnotherPhysicalHostOrAuthorityNamespace() {
        val selected = HostResourceIdentity(host, installation, runtime, true)
        assertEquals(selected, retainHostSelection(selected, listOf(selected)))
        assertNull(retainHostSelection(selected, listOf(selected.copy(hostId = other))))
        assertNull(retainHostSelection(selected, listOf(selected.copy(installationId = other))))
        assertNull(retainHostSelection(selected, listOf(selected.copy(relayAuthorized = false))))
        assertNull(retainHostSelection(selected, listOf(selected.copy(runtimeId = other))))
        assertNull(retainHostSelection(null, listOf(selected)))
    }
    @Test fun reachabilityIsUnknownUntilAnActualProbeCompletes() {
        val groups = groupHostDirectory(listOf(row(installation)), listOf(lan()), false)
        assertTrue(groups.single().runtimes.single().status.isEmpty())
        assertEquals(HostReachability.UNKNOWN, HostPathStatus().reachability)
        val offline = groupHostDirectory(emptyList(), listOf(lan()), true).single().runtimes.single()
        assertFalse(offline.identity.relayAuthorized)
    }
    private fun coordinator(scope: CoroutineScope, authorized: suspend () -> HostManagementApi, pending: suspend () -> Boolean = { false }, connected: (String, HostConnectionPath) -> Unit = { _, _ -> }) =
        HostManagementCoordinator(scope, authorized, pending, {}, {}, connected, { _, _ -> error("unexpected probe") }, { _, _ -> }, { false })

    @Test fun staleAuthorityReplyCannotReopenClosedManagementOrSwitchHosts() = runBlocking {
        val work = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val flow = coordinator(work, {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            throw RemoteRequestException("stale.reply")
        })
        flow.open(true); entered.await()
        flow.close(); release.complete(Unit); yield()
        assertFalse(flow.state.value.open)
        assertNull(flow.state.value.error)
        assertTrue(flow.state.value.hosts.isEmpty())
        work.cancel()
    }
    @Test fun pendingPairingBlocksRevocationBeforeAnyManagementProofOrDelete() = runBlocking {
        val work = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var proofs = 0
        val flow = coordinator(work, { proofs++; throw RemoteRequestException("not.available") }, pending = { true })
        flow.open(true)
        val initial = proofs
        flow.requestRevoke(ManagementTarget.Profile(installation, runtime)); flow.confirmRevoke()
        assertEquals(initial, proofs)
        assertEquals("lan.pending_authorization", flow.state.value.error)
        assertFalse(flow.state.value.mutationUnknown)
        work.cancel()
    }
    @Test fun independentLanIdentityCannotBeSelectedInRelayMode() {
        val work = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var switches = 0
        val flow = coordinator(work, { throw RemoteRequestException("not.available") }, connected = { _, _ -> switches++ })
        flow.open(true)
        flow.directory(groupHostDirectory(emptyList(), listOf(lan()), false), null, null)
        flow.connect(runtime, HostConnectionPath.LAN)
        assertEquals(0, switches)
        assertEquals("lan.separate_authorization", flow.state.value.error)
        work.cancel()
    }
}
