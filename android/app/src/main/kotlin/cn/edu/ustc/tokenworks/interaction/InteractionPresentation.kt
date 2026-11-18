package cn.edu.ustc.tokenworks

/** A host-confirmed approval outcome, not a receipt and not an execution result. */
data class InteractionDecision internal constructor(
    val sessionId: String,
    val sequence: Long,
    val outcome: Outcome,
    internal val tool: ApprovalTool?,
) {
    enum class Outcome { ALLOWED, REJECTED, CANCELLED, UNAVAILABLE }
    override fun toString() = "InteractionDecision($outcome)"
}

/** Native approval requests carry no safe argument projection. Never reuse tool arguments or reason. */
internal enum class ApprovalTool(val label: Int) {
    SHELL(R.string.interaction_tool_shell),
    FILES(R.string.interaction_tool_files),
    SESSION(R.string.interaction_tool_session),
    MESSAGE(R.string.interaction_tool_message),
}

internal fun approvalTool(raw: String?): ApprovalTool? = when (raw) {
    "bash", "shell", "exec_command" -> ApprovalTool.SHELL
    "read", "write", "edit", "remote_files" -> ApprovalTool.FILES
    "remote_create_session" -> ApprovalTool.SESSION
    "remote_prompt" -> ApprovalTool.MESSAGE
    else -> null
}

internal fun interactionDecisions(sessionId: String, state: SessionReadState): List<InteractionDecision> {
    if (state.readOnly) return emptyList()
    val records = state.window?.records ?: return emptyList()
    val asked = mutableMapOf<String, ApprovalTool?>()
    val decisions = ArrayDeque<InteractionDecision>()
    for (record in records) {
        if (record.type != "approval/asked" && record.type != "approval/decided") continue
        val id = (record.data.fields["id"] as? JsonString)?.value?.takeIf { it.isNotBlank() } ?: continue
        if (record.type == "approval/asked") {
            asked[id] = approvalTool((record.data.fields["toolName"] as? JsonString)?.value)
            continue
        }
        // Match in the same current transcript. An out-of-window or duplicate decision is not paired.
        if (!asked.containsKey(id)) continue
        val tool = asked.remove(id)
        val outcome = when ((record.data.fields["outcome"] as? JsonString)?.value) {
            "allowed-once" -> InteractionDecision.Outcome.ALLOWED
            "rejected" -> InteractionDecision.Outcome.REJECTED
            "cancelled" -> InteractionDecision.Outcome.CANCELLED
            "unavailable" -> InteractionDecision.Outcome.UNAVAILABLE
            else -> continue
        }
        if (decisions.size == 20) decisions.removeLast()
        decisions.addFirst(InteractionDecision(sessionId, record.seq, outcome, tool))
    }
    return decisions.toList()
}

internal fun remainingSeconds(remainingMs: Long): Long =
    if (remainingMs <= 0) 0 else 1 + (remainingMs - 1) / 1_000
