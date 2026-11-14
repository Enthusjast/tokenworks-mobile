package cn.edu.ustc.tokenworks

import java.util.UUID

/** Saved before the old-authenticated control PUT; recovery only proves the new key with a read. */
class NoiseDeviceRotationIntent private constructor(
    val operationId: String, val previous: LanCredentials, val replacement: LanCredentials,
) {
    internal fun request(): JsonObject = jsonObject("operationId" to operationId.json(), "expectedDeviceKeyEpoch" to previous.deviceKeyEpoch.json(),
        "newDeviceStaticPublicKey" to replacement.deviceStaticPublicKey.json(), "newDeviceKeyEpoch" to replacement.deviceKeyEpoch.json())
    fun document(): JsonObject = jsonObject("version" to 1L.json(), "operationId" to operationId.json(), "previous" to previous.document(), "replacement" to replacement.document())
    companion object {
        fun create(previous: LanCredentials): NoiseDeviceRotationIntent {
            if (previous.deviceKeyEpoch >= MAX_SAFE_INTEGER) throw LanPairingException("e2e.epoch_exhausted")
            val keys = try { E2eSession.generateStatic() } catch (_: UnsupportedOperationException) { throw LanPairingException("e2e.unsupported") }
            try {
                val manifest = previous.manifest.with("deviceStaticPublicKey" to Contract.base64url(keys.publicKey).json(), "deviceKeyEpoch" to (previous.deviceKeyEpoch + 1).json())
                val replacement = LanCredentials.parse(previous.document().with("manifest" to manifest,
                    "bindingSha256" to JsonDocument.of(manifest).sha256().json(), "deviceStaticPrivateKey" to Contract.base64url(keys.privateKey).json()))
                return NoiseDeviceRotationIntent(UUID.randomUUID().toString(), previous, replacement)
            } finally { keys.privateKey.fill(0) }
        }
        fun parse(root: JsonObject): NoiseDeviceRotationIntent {
            root.exactKeys("version", "operationId", "previous", "replacement"); root.integer("version", 1, 1)
            val previous = LanCredentials.parse(root.obj("previous")); val replacement = LanCredentials.parse(root.obj("replacement"))
            val expected = previous.manifest.with("deviceStaticPublicKey" to replacement.deviceStaticPublicKey.json(), "deviceKeyEpoch" to replacement.deviceKeyEpoch.json())
            if (previous.deviceKeyEpoch >= MAX_SAFE_INTEGER || replacement.deviceKeyEpoch != previous.deviceKeyEpoch + 1 ||
                previous.origin != replacement.origin || previous.tlsPin != replacement.tlsPin ||
                JsonDocument.of(expected).sha256() != replacement.bindingSha256 || previous.deviceStaticPublicKey == replacement.deviceStaticPublicKey)
                throw LanPairingException("lan.identity_mismatch")
            return NoiseDeviceRotationIntent(Contract.uuid(root.string("operationId")), previous, replacement)
        }
    }
}
