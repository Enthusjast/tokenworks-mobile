package cn.edu.ustc.tokenworks

enum class HostConnectionPath { AUTO, LAN, P2P, TURN, RELAY }
enum class HostReachability { UNKNOWN, CHECKING, REACHABLE, UNREACHABLE, AUTH_REQUIRED }
enum class HostManagementNotice { RTC_ALLOWED, RTC_STOPPED, REVOCATION_RECHECKED, REVOCATION_CONFIRMED }
data class HostResourceIdentity(val hostId: String, val installationId: String?, val runtimeId: String, val relayAuthorized: Boolean)
data class HostPathStatus(val reachability: HostReachability = HostReachability.UNKNOWN, val error: String? = null)
data class ManagedHostRuntime(
    val identity: HostResourceIdentity, val alias: String?, val paths: Set<HostConnectionPath>,
    val status: Map<HostConnectionPath, HostPathStatus> = emptyMap(),
    val connectionFailures: Map<HostConnectionPath, Long> = emptyMap(),
)
data class ManagedHostGroup(val hostId: String, val alias: String?, val runtimes: List<ManagedHostRuntime>)

/** LAN self-report cannot create or broaden Relay authority. Only server installation metadata joins paths. */
internal fun groupHostDirectory(relay: List<RuntimeRow>, lan: List<PairedLanDevice>, lanOnly: Boolean): List<ManagedHostGroup> {
    val matched = mutableSetOf<Pair<String, String>>()
    val rows = if (lanOnly) mutableListOf() else relay.map { row ->
        val installation = row.access?.installationId
        val paired = installation != null && lan.any { it.runtimeId == row.id && it.installationId == installation && it.relayBound }
        if (paired) matched += row.id to installation!!
        ManagedHostRuntime(HostResourceIdentity(row.hostId, installation, row.id, true), row.hostAlias,
            if (paired) HostConnectionPath.entries.toSet() else setOf(HostConnectionPath.RELAY))
    }.toMutableList()
    for (device in lan) {
        if ((device.runtimeId to device.installationId) in matched) continue
        rows += ManagedHostRuntime(HostResourceIdentity("lan:${device.installationId}", device.installationId, device.runtimeId, false),
            null, setOf(HostConnectionPath.AUTO, HostConnectionPath.LAN, HostConnectionPath.P2P, HostConnectionPath.TURN))
    }
    return rows.groupBy { it.identity.hostId }.map { (id, runtimes) -> ManagedHostGroup(id, runtimes.first().alias, runtimes) }
}

/** Never picks another runtime/physical host when a path or a grant disappears. */
internal fun retainHostSelection(selected: HostResourceIdentity?, available: List<HostResourceIdentity>): HostResourceIdentity? =
    selected?.takeIf { it in available }

data class HostManagementState(
    val open: Boolean = false, val busy: Boolean = false, val error: String? = null, val notice: HostManagementNotice? = null,
    val devices: List<ManagedDevice> = emptyList(), val devicesCursor: String? = null,
    val installations: List<ManagedInstallation> = emptyList(), val installationsCursor: String? = null,
    val profiles: List<ManagedProfile> = emptyList(), val profileInstallation: String? = null, val profilesCursor: String? = null,
    val grants: List<ManagedGrant> = emptyList(), val grantRuntime: String? = null, val grantsCursor: String? = null,
    val hosts: List<ManagedHostGroup> = emptyList(), val selectedRuntimeId: String? = null,
    val transport: DshTransportState? = null, val confirmation: ManagementTarget? = null,
    val rtcConsents: Map<String, Boolean> = emptyMap(),
    val rtcSupported: Boolean = false,
    val mutationUnknown: Boolean = false, val relayManagementAvailable: Boolean = false,
)
