package com.goim.kmp.pipeline

import com.goim.kmp.domain.InboundEvent
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.ConversationId
import com.goim.kmp.model.Message
import com.goim.kmp.model.MessageStatus
import com.goim.kmp.model.MsgType
import com.goim.kmp.proto.Cmd
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import im.Message as WireMessage

/** 按 cmd 把 [WireMessage] 转为 [InboundEvent]；LoginResp / Heartbeat 走回调不进事件流。 */
class CmdDispatchHandler(
    private val selfUid: String,
    private val onEvent: (InboundEvent) -> Unit,
    private val onLoginResp: () -> Unit = {},
    private val onHeartbeat: () -> Unit = {},
) : InboundHandler {
    override val name = "cmd-dispatch"

    override suspend fun channelRead(ctx: HandlerContext, msg: Any) {
        if (msg !is WireMessage) return ctx.fireChannelRead(msg)
        when (msg.cmd) {
            Cmd.ACK -> onEvent(InboundEvent.Ack(seq = msg.seq, msgId = msg.msg_id))
            Cmd.KICK -> onEvent(InboundEvent.Kick)
            Cmd.CHAT, Cmd.FILE -> onEvent(InboundEvent.MessageReceived(mapMessage(msg)))
            // 仅完成帧；历史条目以 CmdChat / CmdFile 下发。
            Cmd.HISTORY -> onEvent(InboundEvent.HistoryFinished(delivered = msg.seq.toInt()))
            Cmd.UNREAD_COUNT -> parseUnreadCounts(msg.content)?.let { onEvent(InboundEvent.Unread(it)) }
            Cmd.FRIEND_REQUEST -> onEvent(mapFriendRequest(msg))
            Cmd.FRIEND_RESPONSE -> onEvent(
                InboundEvent.FriendResponded(
                    peerUid = msg.from,
                    accepted = parseFriendAction(msg.content),
                    removed = parseObject(msg.content)?.string("action") == "remove",
                ),
            )
            Cmd.HEARTBEAT -> onHeartbeat()
            Cmd.LOGIN_RESP -> onLoginResp()
            else -> ctx.fireChannelRead(msg)
        }
    }

    private fun mapMessage(w: WireMessage) = Message(
        serverMsgId = w.msg_id.takeIf { it != 0L },
        clientSeq = w.seq,
        conversationId = conversationId(w),
        fromUid = w.from,
        toUid = w.to,
        chatType = ChatType.from(w.chat_type),
        msgType = MsgType.from(w.msg_type),
        content = w.content,
        timestampMs = w.timestamp,
        status = MessageStatus.SENT,
        isOutgoing = w.from == selfUid,
    )

    private fun conversationId(w: WireMessage): String {
        if (w.chat_type == ChatType.GROUP.value) return ConversationId.group(w.to)
        val peer = if (w.from == selfUid) w.to else w.from
        return ConversationId.dm(selfUid, peer)
    }

    /**
     * 同一 cmd 承载两种帧：他人请求 `{"from_uid","username"}`（from=请求方），
     * 以及自己请求的回执 `{"status":"sent"}` / `{"error":"..."}`（from=目标）。
     */
    private fun mapFriendRequest(w: WireMessage): InboundEvent {
        val obj = parseObject(w.content)
        val status = obj?.string("status")
        val error = obj?.string("error")
        if (status != null || error != null) return InboundEvent.FriendRequestResult(peerUid = w.from, error = error)
        return InboundEvent.FriendRequestReceived(
            fromUid = obj?.string("from_uid")?.takeIf { it.isNotEmpty() } ?: w.from,
            username = obj?.string("username"),
            timestampMs = w.timestamp,
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** `{"action":"accept"|"reject"}`；兼容 iOS 旧判定（含 accept / "1" / true）。 */
        fun parseFriendAction(content: String): Boolean {
            parseObject(content)?.string("action")?.let { return it.equals("accept", ignoreCase = true) }
            val lower = content.lowercase()
            return "accept" in lower || content == "1" || "true" in lower
        }

        private fun parseObject(content: String): JsonObject? =
            runCatching { json.parseToJsonElement(content).jsonObject }.getOrNull()

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** 服务端载荷：`{"uid":"...","counts":{"peer":1}}`。 */
        fun parseUnreadCounts(content: String): Map<String, Int>? {
            val root = runCatching { json.parseToJsonElement(content).jsonObject }.getOrNull() ?: return null
            val counts = root["counts"] as? JsonObject ?: return null
            return counts.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.doubleOrNull?.let { key to it.toInt() }
            }.toMap()
        }
    }
}
