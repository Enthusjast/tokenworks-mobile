package cn.edu.ustc.tokenworks

class RemoteRequestException(val code: String = "network.unavailable") : IllegalStateException(code)
