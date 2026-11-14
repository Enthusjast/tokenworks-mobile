package cn.edu.ustc.tokenworks

import android.content.Context

/** Existing owner/proof-bound, no-backup Keystore vault; every caller performs these operations on IO. */
class AndroidLanCredentialStore(context: Context, private val owner: LocalDataOwner, private val signerThumbprint: String) {
    private val binding = JsonDocument.of(jsonObject("owner" to owner.root, "proof_key_jkt" to signerThumbprint.json())).sha256()
    private val vault: AccountVault
    init {
        Contract.nonce(signerThumbprint)
        vault = AccountVault(context.applicationContext, "lan-" + binding.take(60))
    }
    class Contents(val credentials: List<LanCredentials>, val pending: LanPairingIntent?, val rotation: NoiseDeviceRotationIntent? = null)
    private class Snapshot(val revision: Long, val contents: Contents)

    private fun snapshot(): Snapshot {
        val entry = vault.read() ?: return Snapshot(0, Contents(emptyList(), null))
        return entry.use {
            if (it.accountId != owner.accountId || it.organizationId != owner.organizationId || it.deploymentHash != owner.deploymentHash)
                throw VaultException(VaultFailure.CONFLICT)
            val bytes = it.bytes()
            try {
                val root = JsonDocument.parse(bytes).root
                if (root["version"] != 3L.json()) throw LanPairingException("e2e.repair_required")
                root.exactKeys("version", "owner", "proofKey", "credentials", "pending", "rotation")
                if (root.string("owner") != owner.fingerprint || root.string("proofKey") != signerThumbprint)
                    throw VaultException(VaultFailure.CONFLICT)
                val values = root.array("credentials")
                if (values.size > MAX_CREDENTIALS) throw VaultException(VaultFailure.CORRUPT)
                val credentials = values.map { value -> LanCredentials.parse(value as? JsonObject ?: throw VaultException(VaultFailure.CORRUPT)) }
                val pending = if (root["pending"] == JsonNull) null else LanPairingIntent.parse(root.obj("pending"))
                val rotation = if (root["rotation"] == JsonNull) null else NoiseDeviceRotationIntent.parse(root.obj("rotation"))
                (credentials + listOfNotNull(pending?.credentials, rotation?.previous, rotation?.replacement)).forEach(::validateOwner)
                if (pending != null && rotation != null || rotation != null && credentials.none { value -> same(value, rotation.previous) })
                    throw VaultException(VaultFailure.CORRUPT)
                if (credentials.map { value -> value.runtimeId }.toSet().size != credentials.size ||
                    pending != null && credentials.any { value -> value.runtimeId == pending.credentials.runtimeId })
                    throw VaultException(VaultFailure.CORRUPT)
                Snapshot(it.revision, Contents(credentials, pending, rotation))
            } finally { bytes.fill(0) }
        }
    }
    fun restore(): Contents = synchronized(lock) { snapshot().contents }
    fun load(): List<LanCredentials> = restore().credentials
    fun loadPending(): LanPairingIntent? = restore().pending

    fun intent(descriptor: LanPairingDescriptor, name: String, signer: DeviceProofSigner, relayIssuer: String?): LanPairingIntent {
        if (signer.publicKey.thumbprint != signerThumbprint) throw VaultException(VaultFailure.CONFLICT)
        val relay = relayIssuer?.let { jsonObject("issuer" to it.json(), "organizationId" to owner.organizationId.json(),
            "deploymentSha256" to owner.deploymentHash.json(), "userId" to owner.userId.json(), "deviceId" to owner.deviceId.json()) }
        return LanPairingIntent.create(descriptor, name, owner, signer, relay)
    }
    private fun validateOwner(credentials: LanCredentials) {
        if (JsonDocument.of(credentials.manifest.obj("owner")).sha256() != owner.fingerprint || credentials.manifest.string("proofJkt") != signerThumbprint)
            throw VaultException(VaultFailure.CONFLICT)
    }

    fun beginRotation(intent: NoiseDeviceRotationIntent, current: () -> Unit = {}) = synchronized(lock) {
        current(); validateOwner(intent.previous); validateOwner(intent.replacement)
        val previous = snapshot()
        if (previous.contents.pending != null || previous.contents.rotation != null) throw LanPairingException("lan.pending_authorization")
        if (previous.contents.credentials.none { same(it, intent.previous) }) throw LanPairingException("lan.identity_mismatch")
        commit(previous.revision, Contents(previous.contents.credentials, null, intent))
    }
    fun promoteRotation(intent: NoiseDeviceRotationIntent) = synchronized(lock) {
        val previous = snapshot()
        val rotation = previous.contents.rotation ?: throw LanPairingException("lan.pending_missing")
        if (JsonDocument.of(rotation.document()).sha256() != JsonDocument.of(intent.document()).sha256()) throw LanPairingException("lan.identity_mismatch")
        commit(previous.revision, Contents(previous.contents.credentials.map { if (same(it, intent.previous)) intent.replacement else it }, null))
    }

    /** Must acknowledge this durable commit before dispatching POST /pair. Never replaces an unresolved intent. */
    fun begin(intent: LanPairingIntent, current: () -> Unit = {}) = synchronized(lock) {
        // Fence inside the same lock as clear: a retired generation cannot recreate a cleared vault.
        current()
        validateOwner(intent.credentials)
        val previous = snapshot()
        if (previous.contents.pending != null || previous.contents.rotation != null) throw LanPairingException("lan.pending_authorization")
        if (previous.contents.credentials.any { it.runtimeId == intent.credentials.runtimeId }) throw LanPairingException("lan.already_paired")
        if (previous.contents.credentials.size >= MAX_CREDENTIALS) throw LanPairingException("lan.storage_full")
        commit(previous.revision, Contents(previous.contents.credentials, intent))
    }

    /** Atomic promotion removes the private one-time descriptor only after Host identity is established. */
    fun promote(credentials: LanCredentials) = synchronized(lock) {
        validateOwner(credentials)
        val previous = snapshot()
        val pending = previous.contents.pending ?: run {
            if (previous.contents.credentials.any { same(it, credentials) }) return@synchronized
            throw LanPairingException("lan.pending_missing")
        }
        if (!same(pending.credentials, credentials)) throw LanPairingException("lan.identity_mismatch")
        commit(previous.revision, Contents(previous.contents.credentials + credentials, null))
    }

    fun save(credentials: LanCredentials) = synchronized(lock) {
        validateOwner(credentials)
        val previous = snapshot()
        previous.contents.credentials.firstOrNull { it.runtimeId == credentials.runtimeId }?.let {
            if (same(it, credentials)) return@synchronized
            throw LanPairingException("lan.already_paired")
        }
        if (previous.contents.pending?.credentials?.runtimeId == credentials.runtimeId) throw LanPairingException("lan.pending_authorization")
        if (previous.contents.credentials.size >= MAX_CREDENTIALS) throw LanPairingException("lan.storage_full")
        commit(previous.revision, Contents(previous.contents.credentials + credentials, previous.contents.pending, previous.contents.rotation))
    }

    /** Only acknowledged remote revocation or an explicitly disclosed local forget may call this. */
    fun remove(runtimeId: String) = synchronized(lock) {
        val previous = snapshot()
        val kept = previous.contents.credentials.filterNot { it.runtimeId == runtimeId }
        val pending = previous.contents.pending?.takeUnless { it.credentials.runtimeId == runtimeId }
        val rotation = previous.contents.rotation?.takeUnless { it.previous.runtimeId == runtimeId }
        if (kept.size != previous.contents.credentials.size || pending !== previous.contents.pending || rotation !== previous.contents.rotation)
            commit(previous.revision, Contents(kept, pending, rotation))
    }
    fun clear() = synchronized(lock) { vault.clear() }

    private fun same(a: LanCredentials, b: LanCredentials) = JsonDocument.of(a.document()).canonicalText() == JsonDocument.of(b.document()).canonicalText()
    private fun commit(revision: Long, contents: Contents) {
        val bytes = JsonDocument.of(jsonObject("version" to 3L.json(), "owner" to owner.fingerprint.json(),
            "proofKey" to signerThumbprint.json(), "credentials" to JsonArray(contents.credentials.map(LanCredentials::document)),
            "pending" to (contents.pending?.document() ?: JsonNull), "rotation" to (contents.rotation?.document() ?: JsonNull))).canonicalBytes()
        try { vault.save(owner.accountId, owner.organizationId, owner.deploymentHash, bytes, revision) }
        finally { bytes.fill(0) }
    }
    companion object {
        private const val MAX_CREDENTIALS = 16
        private val lock = Any()
    }
}
