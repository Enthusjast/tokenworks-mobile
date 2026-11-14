package cn.edu.ustc.tokenworks

/** Short-lived authoritative lease, sent to Relay and inside the pinned Host's encrypted AUTH only. */
class DshLease(val token: String, val id: String, val expiresAt: Long) {
    fun usable(now: Long = System.currentTimeMillis()) = now + 30_000 < expiresAt
    override fun toString() = "DshLease($id)"
}
