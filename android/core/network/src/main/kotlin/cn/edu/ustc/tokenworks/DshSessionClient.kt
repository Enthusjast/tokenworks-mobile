package cn.edu.ustc.tokenworks

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A selectable model as advertised by the native catalog. */
data class DshModelOption(
    val provider: String, val model: String, val name: String, val description: String?,
    val reasoningEfforts: List<String>, val defaultReasoningEffort: String?,
) {
    val key get() = "$provider/$model"
    override fun toString() = name
}

/** One provider group plus the models the host could not load. */
data class DshModelCatalog(
    val default: DshModelOption?, val groups: List<DshModelGroup>, val unavailable: List<String>,
) {
    val options: List<DshModelOption> get() = groups.flatMap { it.models }
    fun find(key: String): DshModelOption? = options.firstOrNull { it.key == key }
    override fun toString() = "DshModelCatalog(groups=${groups.size})"
}

data class DshModelGroup(val name: String, val models: List<DshModelOption>) {
    override fun toString() = name
}

/**
 * Native control-plane client: directory, creation and the model catalog. Lease
 * acquisition is cached per runtime because the Relay issues a short-lived secret
 * that every subsequent call has to present.
 */
class DshSessionClient(private val api: RemoteApi, private val now: () -> Long = System::currentTimeMillis) : AutoCloseable {
    private val mutex = Mutex()
    private val leases = mutableMapOf<String, DshLease>()

    private suspend fun lease(runtimeId: String): DshLease = mutex.withLock {
        leases[runtimeId]?.takeIf { it.usable(now()) } ?: api.nativeLease(runtimeId).also { leases[runtimeId] = it }
    }

    private suspend inline fun <reified I> call(endpoint: String, codec: DshCodec<I>, input: I, runtimeId: String): JsonValue {
        val token = lease(runtimeId)
        try {
            return api.native(endpoint, codec.encode(input) as JsonObject, runtimeId, token)
        } catch (error: RemoteRequestException) {
            // A revoked or expired lease must not be retried with the same secret.
            if (error.code in setOf("auth.required", "lease.expired", "lease.unknown", "auth.invalid")) mutex.withLock { leases.remove(runtimeId) }
            throw error
        }
    }

    /** The caller's visible sessions, exactly as dsh filtered them. */
    suspend fun sessions(runtimeId: String): SessionDirectoryPage {
        val value = call("session/list", DshMethods.SessionList.input,
            DshSessionListInput(DshSessionListInputRequest()), runtimeId)
        val result = DshMethods.SessionList.output.decode(value)
        return SessionDirectoryPage(result.items.map { SessionDirectoryEntry.from(it) })
    }

    /** Creates a session; omitted arguments stay absent so dsh applies its own defaults. */
    suspend fun createSession(runtimeId: String, workspaceId: String? = null, cwd: String? = null, agentPreset: String? = null): String {
        val value = call("session/create", DshMethods.SessionCreate.input, DshSessionCreateInput(
            DshSessionCreateInputRequest(
                workspaceId?.let { DshOptional.Value(it) } ?: DshOptional.Missing,
                cwd?.let { DshOptional.Value(it) } ?: DshOptional.Missing,
                DshOptional.Missing,
                agentPreset?.let { DshOptional.Value(it) } ?: DshOptional.Missing,
            ),
        ), runtimeId)
        return DshMethods.SessionCreate.output.decode(value).sessionId
    }

    suspend fun modelCatalog(runtimeId: String): DshModelCatalog {
        val value = call("session/modelCatalog", DshMethods.SessionModelCatalog.input, emptyMap<String, JsonValue>(), runtimeId)
        return decode(value)
    }

    /**
     * P1-7: terminal control plane. The native controller resolves the owning
     * Agent through the Gateway; `agentId` is the session's agent identity as
     * projected in follow records.
     */
    suspend fun terminals(runtimeId: String, agentId: String): List<TerminalInfo> {
        val value = call("terminal/list", DshMethods.TerminalList.input, DshTerminalListInput(agentId), runtimeId)
        return (value as? JsonArray ?: throw RemoteRequestException("network.invalid_response")).values.mapNotNull { entry ->
            decodeTerminalInfo(entry as? JsonObject ?: return@mapNotNull null)
        }
    }

    /** Allocates a shell once for `id`; a repeated id returns the existing terminal unchanged. */
    suspend fun createTerminal(runtimeId: String, agentId: String, id: String, cols: Int, rows: Int, shellPath: String? = null): TerminalInfo {
        val request = DshTerminalCreateInputRequest(
            shellPath?.let { DshOptional.Value(it) } ?: DshOptional.Missing, id, cols.toDouble(), rows.toDouble(),
        )
        val value = call("terminal/create", DshMethods.TerminalCreate.input, DshTerminalCreateInput(agentId, request), runtimeId)
        return decodeTerminalInfo(value as? JsonObject ?: throw RemoteRequestException("network.invalid_response"))
    }

    suspend fun closeTerminal(runtimeId: String, agentId: String, id: String) {
        call("terminal/close", DshMethods.TerminalClose.input, DshTerminalCloseInput(agentId, id), runtimeId)
    }

    /** Input byte budget and buffered-frame cap for the attach stream. */
    suspend fun terminalEnvironment(runtimeId: String, agentId: String): Pair<Int, Int> {
        val value = call("terminal/environment", DshMethods.TerminalEnvironment.input, DshTerminalEnvironmentInput(agentId), runtimeId)
        val root = value as? JsonObject ?: throw RemoteRequestException("network.invalid_response")
        return Pair(root.integer("maxInputBytes", 1).toInt(), root.integer("maxBufferedBytes", 1).toInt())
    }

    override fun close() { leases.clear() }
}

private fun DshSessionClient.decode(value: JsonValue): DshModelCatalog {
    val catalog = value as? JsonObject ?: throw RemoteRequestException("network.invalid_response")
    fun option(provider: String, item: JsonObject): DshModelOption {
        val reasoning = item.fields["reasoning"] as? JsonObject
        val efforts = reasoning?.let { node ->
            node.array("efforts").mapNotNull { entry -> (entry as? JsonObject)?.fields["id"]?.let { (it as JsonString).value } }
        } ?: emptyList()
        return DshModelOption(
            provider = provider,
            model = (item["id"] as? JsonString)?.value ?: throw RemoteRequestException("network.invalid_response"),
            name = (item["name"] as? JsonString)?.value ?: provider,
            description = (item.fields["description"] as? JsonString)?.value,
            reasoningEfforts = efforts,
            defaultReasoningEffort = (reasoning?.fields?.get("defaultEffort") as? JsonString)?.value,
        )
    }
    val default = catalog.obj("default")
    val defaultProvider = (default["provider"] as? JsonString)?.value
    val defaultModel = (default["model"] as? JsonString)?.value
    val groups = catalog.array("groups").mapNotNull { entry ->
        val group = entry as? JsonObject ?: return@mapNotNull null
        val provider = (group["id"] as? JsonString)?.value ?: return@mapNotNull null
        DshModelGroup(
            name = (group["name"] as? JsonString)?.value ?: provider,
            models = group.array("models").mapNotNull { model -> (model as? JsonObject)?.let { option(provider, it) } },
        )
    }
    val failures = catalog.array("failures").mapNotNull { entry -> (entry as? JsonObject)?.let { (it["name"] as? JsonString)?.value ?: (it["id"] as? JsonString)?.value } }
    val selected = defaultProvider?.let { provider -> defaultModel?.let { model -> groups.firstOrNull { it.name == provider }?.models?.firstOrNull { it.model == model } } }
    return DshModelCatalog(selected, groups, failures)
}

private fun decodeTerminalInfo(root: JsonObject): TerminalInfo {
    val shell = root.obj("shell")
    return TerminalInfo(
        root.string("id"), root.string("title"), shell.string("path"), root.string("cwd"),
        root.integer("cols", 2, 512).toInt(), root.integer("rows", 2, 512).toInt(),
        root.string("state"), (root.fields["exitCode"] as? JsonNumber)?.value?.toInt(), (root.fields["error"] as? JsonString)?.value,
    )
}
