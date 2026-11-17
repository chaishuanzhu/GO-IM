package com.goim.sdk.core

import com.goim.kmp.domain.ConnectionRepository
import com.goim.kmp.domain.ConversationRepository
import com.goim.kmp.domain.FileRepository
import com.goim.kmp.domain.FriendRepository
import com.goim.kmp.domain.GroupRepository
import com.goim.kmp.domain.InboundEvent
import com.goim.kmp.domain.MessageRepository
import com.goim.kmp.domain.OutboundEnvelope
import com.goim.kmp.domain.ProfileRepository
import com.goim.kmp.domain.SearchRepository
import com.goim.kmp.domain.StickerRepository
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.Message
import com.goim.kmp.model.User
import com.goim.kmp.repository.UserSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance

/**
 * 已打开会话的稳定门面：暴露领域仓库与常用发送入口。
 * 内部持有 [UserSession]；宿主不应依赖 [UserSession] 的构造细节。
 *
 * [connection.inboundEvents] 为全量 raw inbound（多订阅，可与 Session 内部泵并行 collect）。
 */
@GoImSdkPreview
class GoImSession internal constructor(
    internal val userSession: UserSession,
) {
    val user: User get() = userSession.user

    val messages: MessageRepository get() = userSession.messages
    val conversations: ConversationRepository get() = userSession.conversations
    val connection: ConnectionRepository get() = userSession.connection
    val friends: FriendRepository get() = userSession.friends
    val groups: GroupRepository get() = userSession.groups
    val files: FileRepository get() = userSession.files
    val profiles: ProfileRepository get() = userSession.profiles
    val stickers: StickerRepository get() = userSession.stickers
    val search: SearchRepository get() = userSession.search

    /** CmdSignal 入站（在线-only；不落库）。 */
    val signals: Flow<InboundEvent.Signal> =
        connection.inboundEvents.filterIsInstance()

    /** 前台恢复：补连。 */
    suspend fun onForeground() {
        runCatching { connection.ensureConnected() }
    }

    /** 后台占位（当前实现无额外动作；保留给宿主统一生命周期）。 */
    fun onBackground() = Unit

    suspend fun sendText(peer: String, text: String, chatType: ChatType = ChatType.SINGLE): Message =
        messages.sendText(peer, chatType, text, user)

    /**
     * 发送在线信令（CmdSignal）。[dataJson] 为 `data` 对象的 JSON（默认 `{}`）。
     * 群/聊天室默认 [ChatType.GROUP]；单聊传 [ChatType.SINGLE] 且 [to] 为对端 uid。
     */
    suspend fun sendSignal(
        to: String,
        type: String,
        dataJson: String = "{}",
        chatType: ChatType = ChatType.GROUP,
    ) {
        connection.send(OutboundEnvelope.Signal(to = to, type = type, dataJson = dataJson, chatType = chatType))
    }
}
