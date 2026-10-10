package com.goim.kmp.proto

/** 命令类型，取值与服务端 `api/proto/message.go` 一致。 */
object Cmd {
    const val NONE = 0
    const val CHAT = 1
    const val ACK = 2
    const val LOGIN = 3
    const val LOGIN_RESP = 4
    const val OFFLINE = 5
    const val HEARTBEAT = 6
    const val KICK = 7
    const val HISTORY = 8
    const val READ_RECEIPT = 9
    const val UNREAD_COUNT = 10
    const val SEARCH = 11
    const val GROUP_CREATE = 12
    const val GROUP_JOIN = 13
    const val GROUP_LEAVE = 14
    const val GROUP_INFO = 15
    const val GROUP_LIST = 16
    const val FILE = 17
    const val GROUP_INVITE_MEMBER = 18
    const val RECALL = 19
    const val FRIEND_REQUEST = 20
    const val FRIEND_RESPONSE = 21
    const val TYPING = 22
    const val FORWARD = 23
    const val EDIT = 24
}

object ChatType {
    const val SINGLE = 1
    const val GROUP = 2
}

object MsgType {
    const val TEXT = 1
    const val IMAGE = 2
    const val VOICE = 3
    const val VIDEO = 4
    const val FILE = 5
    const val STICKER = 6
}
