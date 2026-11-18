package cn.edu.ustc.tokenworks

/** RTC carries the same authenticated native protocol, with distinct direct and relay-only routes. */
class DshRtcTransport internal constructor(
    credentials: LanCredentials,
    signer: DeviceProofSigner,
    path: DshTransportPath,
    outer: CarrierOuterDialer,
    api: RemoteApi?,
) : EncryptedDshTransport by rtcRoute(credentials, signer, path, outer, api)

private fun rtcRoute(
    credentials: LanCredentials,
    signer: DeviceProofSigner,
    path: DshTransportPath,
    outer: CarrierOuterDialer,
    api: RemoteApi?,
): NativeRouteTransport {
    val routeKind = when (path) {
        DshTransportPath.P2P -> "p2p"
        DshTransportPath.TURN -> "turn"
        else -> error("RTC requires a P2P or TURN path")
    }
    return NativeRouteTransport(credentials, path, api) { lease ->
        CarrierDialer { purpose -> CarrierSocket.open(outer, credentials, signer, routeKind, purpose, lease) }
    }
}
