package cn.edu.ustc.tokenworks

/** One decoded content group on a session subscription; consumers route by [type]. */
class SessionContent(val type: String, val requestId: String, val syncId: String, val payload: JsonObject)
