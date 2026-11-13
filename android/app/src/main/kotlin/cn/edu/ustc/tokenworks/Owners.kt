package cn.edu.ustc.tokenworks

/** The on-device data owner is also the presentation owner of every stored preference. */
typealias CommandOwner = LocalDataOwner

class CommandDataException(val code: String) : IllegalStateException(code)
