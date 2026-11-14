package cn.edu.ustc.tokenworks

/** Both selected routes must authenticate the same persisted manifest before native dispatch. */
interface EncryptedDshTransport : DshTransport {
    val installationId: String
    val bindingIdentity: String
    suspend fun probeIdentity()
}
