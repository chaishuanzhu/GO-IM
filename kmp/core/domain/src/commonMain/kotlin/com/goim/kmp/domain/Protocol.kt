package com.goim.kmp.domain

import com.goim.kmp.model.ChatType
import com.goim.kmp.model.Message

/** Pipeline 按 cmd 分派出的入站事件（对应 iOS `InboundEvent`）。 */
sealed interface InboundEvent {
    data class MessageReceived(val message: Message) : InboundEvent

    data class Ack(val seq: Long, val msgId: Long) : InboundEvent

    data object Kick : InboundEvent

    /** 服务端返回的未读数：key 为对端 UID 或群 ID。 */
    data class Unread(val counts: Map<String, Int>) : InboundEvent

    /** CmdHistory 完成帧；`delivered` 为本页条数。 */
    data class HistoryFinished(val delivered: Int) : InboundEvent

    /** 他人发来的好友请求（在线推送或离线补发）。 */
    data class FriendRequestReceived(val fromUid: String, val username: String?, val timestampMs: Long) : InboundEvent

    /** 自己发出 CmdFriendRequest 后网关的回执；[error] 为 null 表示已送达。 */
    data class FriendRequestResult(val peerUid: String, val error: String?) : InboundEvent

    /** 对方处理了我发出的好友请求。 */
    data class FriendResponded(val peerUid: String, val accepted: Boolean) : InboundEvent
}

/** 出站信封（对应 iOS `OutboundEnvelope`）。 */
sealed interface OutboundEnvelope {
    data class Chat(val message: Message) : OutboundEnvelope

    data class File(val message: Message) : OutboundEnvelope

    data object Offline : OutboundEnvelope

    data class History(val peer: String, val before: Long?, val limit: Int, val chatType: ChatType) : OutboundEnvelope

    data class ReadReceipt(val to: String, val chatType: ChatType) : OutboundEnvelope

    data object UnreadCount : OutboundEnvelope

    data object Heartbeat : OutboundEnvelope

    data class FriendRequest(val to: String) : OutboundEnvelope

    data class FriendResponse(val to: String, val accept: Boolean) : OutboundEnvelope
}
