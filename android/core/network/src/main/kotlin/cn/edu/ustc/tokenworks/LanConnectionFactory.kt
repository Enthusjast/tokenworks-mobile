package cn.edu.ustc.tokenworks

import okhttp3.OkHttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps the platform HTTP dependency at the network boundary, not in the account/UI lifecycle. */
class LanConnectionFactory private constructor(private val operatorClient: OkHttpClient) {
    suspend fun pair(signer: DeviceProofSigner, intent: LanPairingIntent): LanCredentials =
        LanPairingClient(signer, operatorClient).pair(intent)

    suspend fun reconcile(signer: DeviceProofSigner, credentials: LanCredentials) =
        LanPairingClient(signer, operatorClient).reconcile(credentials)

    fun transport(credentials: LanCredentials, signer: DeviceProofSigner, api: RemoteApi? = null): DshLanTransport =
        DshLanTransport(credentials, signer, operatorClient, api)

    fun relayTransport(api: RemoteApi, credentials: LanCredentials): DshRelayTransport = DshRelayTransport(api, credentials)

    suspend fun rotate(signer: DeviceProofSigner, intent: NoiseDeviceRotationIntent) =
        transport(intent.previous, signer).use { it.rotate(intent) }

    suspend fun reconcileRotation(signer: DeviceProofSigner, intent: NoiseDeviceRotationIntent) =
        transport(intent.replacement, signer).use { it.reconcileEncrypted() }

    fun rtcTransport(credentials: LanCredentials, signer: DeviceProofSigner, path: DshTransportPath,
        outer: CarrierOuterDialer, api: RemoteApi?): DshRtcTransport =
        DshRtcTransport(credentials, signer, path, outer, api)

    fun pairingControl(credentials: LanCredentials, signer: DeviceProofSigner, api: RemoteApi?): PairingControlClient {
        val bound = credentials.manifest.fields["relay"] is JsonObject
        val dialer = if (!bound) LanCarrierDialer(credentials, signer, operatorClient) else {
            val authority = api ?: throw DshFailure("e2e.relay_authority_required")
            val mutex = Mutex()
            var lease: DshLease? = null
            CarrierDialer { purpose ->
                val token = mutex.withLock { lease ?: authority.boundNativeLease(credentials).also { lease = it } }
                authority.openCarrier(credentials.runtimeId, token, credentials, purpose)
            }
        }
        return PairingControlClient(credentials, signer, NativeHttpClient(dialer))
    }

    companion object {
        fun create(): LanConnectionFactory = LanConnectionFactory(OkHttpClient())
        /** Preserves a legitimately configured operator CA; normal hostname checks and pins still apply. */
        fun withOperatorClient(client: OkHttpClient): LanConnectionFactory = LanConnectionFactory(client)
    }
}
