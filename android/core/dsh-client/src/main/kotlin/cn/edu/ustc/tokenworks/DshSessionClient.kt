package cn.edu.ustc.tokenworks


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

/** Native control-plane client. Credential lifetime belongs to the injected route. */
class DshSessionClient(private val transport: DshTransport) {
    private suspend fun <I> call(endpoint: String, codec: DshCodec<I>, input: I): JsonValue =
        transport.native(endpoint, codec.encode(input) as JsonObject)

    /** The caller's visible sessions, exactly as dsh filtered them. */
    suspend fun sessions(): SessionDirectoryPage {
        val value = call("session/list", DshMethods.SessionList.input,
            DshSessionListInput(DshSessionListInputRequest()))
        val result = DshMethods.SessionList.output.decode(value)
        return SessionDirectoryPage(result.items.map { SessionDirectoryEntry.from(it) })
    }

    /** Creates a session; omitted arguments stay absent so dsh applies its own defaults. */
    suspend fun createSession(workspaceId: String? = null, cwd: String? = null, agentPreset: String? = null): String {
        val value = call("session/create", DshMethods.SessionCreate.input, DshSessionCreateInput(
            DshSessionCreateInputRequest(
                workspaceId?.let { DshOptional.Value(it) } ?: DshOptional.Missing,
                cwd?.let { DshOptional.Value(it) } ?: DshOptional.Missing,
                DshOptional.Missing,
                agentPreset?.let { DshOptional.Value(it) } ?: DshOptional.Missing,
            ),
        ))
        return DshMethods.SessionCreate.output.decode(value).sessionId
    }

    suspend fun modelCatalog(): DshModelCatalog {
        val value = call("session/modelCatalog", DshMethods.SessionModelCatalog.input, emptyMap<String, JsonValue>())
        return decode(value)
    }

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

