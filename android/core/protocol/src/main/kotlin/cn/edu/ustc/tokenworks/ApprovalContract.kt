package cn.edu.ustc.tokenworks

import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.Base64

const val APPROVAL_CONTEXT_DOMAIN = "tokenworks-mobile/approval-context/v1"
const val APPROVAL_DECISION_DOMAIN = "tokenworks-mobile/approval-decision/v1"
const val APPROVAL_JWS_TYPE = "tokenworks-mobile-approval-decision+jws"
const val MAX_APPROVAL_LIFETIME_MS = 180_000L
const val APPROVAL_CLOCK_SKEW_MS = 5_000L
const val MAX_DECISION_AGE_MS = 30_000L

/** Holds the complete immutable context, including all arguments and resource facts. */
class ApprovalContext private constructor(val document: JsonDocument) {
    val root: JsonObject get() = document.root
    val bindingSha256: String = document.sha256()
    val createdAt: Long = Contract.utc(root.string("created_at"))
    val expiresAt: Long = Contract.utc(root.string("expires_at"))
    fun current(now: Long): Boolean = now >= 0 && createdAt <= now + APPROVAL_CLOCK_SKEW_MS && expiresAt > now

    companion object {
        fun parse(bytes: ByteArray): ApprovalContext = from(JsonDocument.parse(bytes))
        fun parse(text: String): ApprovalContext = from(JsonDocument.parse(text))
        fun from(document: JsonDocument): ApprovalContext {
            val root = document.root
            root.exactKeys("domain", "protocol_version", *Contract.identityFields.toTypedArray(), "dsh_session_id", "call_id", "tool", "arguments", "workspace", "resources", "nonce", "policy_version", "required_assurance", "created_at", "expires_at")
            if (root.string("domain") != APPROVAL_CONTEXT_DOMAIN || root.integer("protocol_version", 1, 1) != 1L) throw ProtocolException()
            Contract.identity(root)
            Contract.boundedText(root.string("dsh_session_id"), 2048)
            Contract.boundedText(root.string("call_id"), 2048)
            Contract.boundedText(root.string("tool"), 128)
            val workspace = root.obj("workspace")
            workspace.exactKeys("workspace_id", "root_sha256")
            Contract.uuid(workspace.string("workspace_id")); Contract.digest(workspace.string("root_sha256"))
            val resources = root.array("resources")
            if (resources.size > 256) throw ProtocolException()
            val seen = hashSetOf<String>()
            resources.forEach { value ->
                val resource = value as? JsonObject ?: throw ProtocolException()
                resource.exactKeys("kind", "resource_id", "version_sha256")
                val kind = resource.string("kind")
                val id = Contract.boundedText(resource.string("resource_id"), 2048)
                if (kind !in setOf("file", "repository", "artifact") || !seen.add("$kind\u0000$id")) throw ProtocolException()
                Contract.digest(resource.string("version_sha256"))
            }
            Contract.nonce(root.string("nonce")); root.integer("policy_version")
            val assurance = root.obj("required_assurance")
            assurance.exactKeys("profile", "max_auth_age_seconds")
            if (!Regex("[a-z0-9][a-z0-9./_-]{0,127}").matches(assurance.string("profile"))) throw ProtocolException()
            assurance.integer("max_auth_age_seconds", 0, 900)
            val context = ApprovalContext(document)
            if (context.expiresAt - context.createdAt !in 1..MAX_APPROVAL_LIFETIME_MS) throw ProtocolException()
            return context
        }
    }
}

enum class ApprovalChoice(val wire: String) { ALLOW_ONCE("allow_once"), REJECT("reject") }

class ApprovalDecision private constructor(val document: JsonDocument) {
    val root: JsonObject get() = document.root
    val issuedAt: Long = Contract.utc(root.string("issued_at"))
    val expiresAt: Long = Contract.utc(root.string("expires_at"))
    val choice: ApprovalChoice = ApprovalChoice.entries.first { it.wire == root.string("decision") }

    fun matches(context: ApprovalContext, now: Long): Boolean {
        if (!context.current(now)) return false
        for (field in Contract.identityFields + listOf("policy_version", "nonce")) if (root[field] != context.root[field]) return false
        return root.string("binding_sha256") == context.bindingSha256 && issuedAt <= now + APPROVAL_CLOCK_SKEW_MS && issuedAt >= now - MAX_DECISION_AGE_MS &&
            issuedAt >= context.createdAt - APPROVAL_CLOCK_SKEW_MS && expiresAt > now && expiresAt > issuedAt && expiresAt <= context.expiresAt
    }

    companion object {
        fun parse(bytes: ByteArray): ApprovalDecision = from(JsonDocument.parse(bytes))
        fun from(document: JsonDocument): ApprovalDecision {
            val root = document.root
            root.exactKeys("domain", "protocol_version", *Contract.identityFields.toTypedArray(), "binding_sha256", "decision", "user_id", "device_id", "key_version", "policy_version", "nonce", "issued_at", "expires_at")
            if (root.string("domain") != APPROVAL_DECISION_DOMAIN || root.integer("protocol_version", 1, 1) != 1L || root.string("decision") !in ApprovalChoice.entries.map { it.wire }) throw ProtocolException()
            Contract.identity(root); Contract.uuid(root.string("user_id")); Contract.uuid(root.string("device_id"))
            Contract.digest(root.string("binding_sha256")); Contract.nonce(root.string("nonce"))
            root.integer("key_version"); root.integer("policy_version")
            return ApprovalDecision(document)
        }

        fun create(context: ApprovalContext, choice: ApprovalChoice, userId: String, deviceId: String, keyVersion: Long, now: Long, expiresAt: Long = context.expiresAt): ApprovalDecision {
            val values = Contract.identityFields.associateWith { context.root[it] }.toMutableMap()
            values.putAll(mapOf(
                "domain" to APPROVAL_DECISION_DOMAIN.json(), "protocol_version" to 1L.json(),
                "binding_sha256" to context.bindingSha256.json(), "decision" to choice.wire.json(),
                "user_id" to userId.json(), "device_id" to deviceId.json(), "key_version" to keyVersion.json(),
                "policy_version" to context.root["policy_version"], "nonce" to context.root["nonce"],
                "issued_at" to Contract.timestamp(now).json(), "expires_at" to Contract.timestamp(expiresAt).json(),
            ))
            return from(JsonDocument.of(JsonObject(values))).also { if (!it.matches(context, now)) throw ProtocolException() }
        }
    }
}

fun approvalKeyId(deviceId: String, keyVersion: Long): String {
    Contract.uuid(deviceId)
    if (keyVersion !in 1..MAX_SAFE_INTEGER) throw ProtocolException()
    return "tokenworks-mobile-approval/$deviceId/$keyVersion"
}
