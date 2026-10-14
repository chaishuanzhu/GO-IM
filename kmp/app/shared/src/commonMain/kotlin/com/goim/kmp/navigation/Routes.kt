package com.goim.kmp.navigation

import com.goim.kmp.feature.chat.MessageFocus
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.Conversation
import com.goim.kmp.model.SearchHit
import kotlinx.serialization.Serializable

@Serializable
data object LoginRoute

@Serializable
data object ServerSettingsRoute

@Serializable
data object HomeRoute

@Serializable
data object NewFriendsRoute

@Serializable
data object DeveloperRoute

@Serializable
data object GroupsRoute

@Serializable
data class GroupInfoRoute(val groupId: String, val name: String)

/** [fromChat]：从该好友的聊天页进入，「发消息」直接返回即可。 */
@Serializable
data class FriendInfoRoute(val uid: String, val name: String, val fromChat: Boolean = false)

/** `focus*` 来自搜索跳转：打开后定位并高亮该条消息。 */
@Serializable
data class ChatRoute(
    val conversationId: String,
    val peer: String,
    val chatType: Int,
    val title: String,
    val focusServerMsgId: Long = 0,
    val focusLocalId: String? = null,
    val focusTimestamp: Long = 0,
) {
    fun toConversation() = Conversation(
        id = conversationId,
        chatType = ChatType.from(chatType),
        title = title,
        peerOrGroupId = peer,
    )

    fun focus(): MessageFocus? =
        if (focusServerMsgId > 0 || focusLocalId != null) MessageFocus(focusServerMsgId, focusLocalId, focusTimestamp) else null

    companion object {
        fun of(conversation: Conversation) = ChatRoute(
            conversationId = conversation.id,
            peer = conversation.peerOrGroupId,
            chatType = conversation.chatType.value,
            title = conversation.title,
        )

        fun of(hit: SearchHit, title: String) = ChatRoute(
            conversationId = hit.conversationId,
            peer = hit.peerOrGroupId,
            chatType = hit.chatType.value,
            title = title,
            focusServerMsgId = hit.serverMsgId,
            focusLocalId = hit.localMessageId,
            focusTimestamp = hit.timestampMs,
        )
    }
}
