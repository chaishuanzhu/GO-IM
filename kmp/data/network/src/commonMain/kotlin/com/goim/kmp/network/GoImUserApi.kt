package com.goim.kmp.network

import com.goim.kmp.common.DomainError
import com.goim.kmp.model.AdminChatPage
import com.goim.kmp.model.AdminConversation
import com.goim.kmp.model.AdminCreatedUser
import com.goim.kmp.model.AdminMessage
import com.goim.kmp.model.AdminSender
import com.goim.kmp.model.AdminSettings
import com.goim.kmp.model.AdminStats
import com.goim.kmp.model.AdminUserPage
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.ConversationId
import com.goim.kmp.model.EmailCodePurpose
import com.goim.kmp.model.Friend
import com.goim.kmp.model.FriendRequest
import com.goim.kmp.model.FriendRequestStatus
import com.goim.kmp.model.Group
import com.goim.kmp.model.GroupMember
import com.goim.kmp.model.MultiDevicePolicy
import com.goim.kmp.model.SearchHit
import com.goim.kmp.model.User
import com.goim.kmp.model.UserProfile
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
internal data class GroupDto(
    val id: String? = null,
    @SerialName("group_id") val groupId: String? = null,
    val name: String = "",
    @SerialName("owner_uid") val ownerUid: String = "",
    val members: List<String>? = null,
    @SerialName("member_count") val memberCount: Int? = null,
) {
    /** `/group/create` 返回 `group_id`，`/group/list` 返回 `id`。 */
    fun toDomain(): Group {
        val gid = id?.takeIf { it.isNotEmpty() } ?: groupId?.takeIf { it.isNotEmpty() }
            ?: throw DomainError.Server(200, "响应缺少群 ID")
        return Group(gid, name, ownerUid, memberCount ?: members?.size ?: 0)
    }
}

@Serializable
internal data class GroupListDto(val groups: List<GroupDto>? = null)

@Serializable
internal data class GroupMembersDto(
    @SerialName("group_id") val groupId: String = "",
    val members: List<String>? = null,
    val aliases: Map<String, String>? = null,
)

@Serializable
internal data class FriendRowDto(
    val uid: String? = null,
    @SerialName("friend_uid") val friendUid: String = "",
    val nickname: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val signature: String? = null,
    val remark: String? = null,
)

@Serializable
internal data class PendingRequestDto(
    @SerialName("from_uid") val fromUid: String,
    val nickname: String? = null,
    /** 1.3.0 之前的网关字段。 */
    val username: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
)

@Serializable
internal data class ProfileDto(
    val uid: String,
    val nickname: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val email: String? = null,
    val signature: String? = null,
)

@Serializable
internal data class ProfileListDto(val users: List<ProfileDto>? = null)

@Serializable
internal data class FriendListDto(
    val friends: List<FriendRowDto>? = null,
    @SerialName("pending_requests") val pendingRequests: List<PendingRequestDto>? = null,
    @SerialName("request_history") val requestHistory: List<RequestRecordDto>? = null,
)

/** 好友请求记录；`nickname` / `avatar_url` 是对方的，`status` 0 待处理 / 1 已接受 / 2 已拒绝。 */
@Serializable
internal data class RequestRecordDto(
    @SerialName("from_uid") val fromUid: String,
    @SerialName("to_uid") val toUid: String,
    val status: Int = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    val nickname: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

/** `proto.Message` 的 encoding/json 输出：字段均 omitempty，`msg_id` 为数字。 */
@Serializable
internal data class SearchMessageDto(
    @SerialName("msg_id") val msgId: JsonElement? = null,
    val from: String = "",
    val to: String = "",
    @SerialName("chat_type") val chatType: Int = ChatType.SINGLE.value,
    val content: String = "",
    val timestamp: Long = 0,
)

@Serializable
internal data class SearchResponseDto(val messages: List<SearchMessageDto>? = null)

@Serializable
internal data class PresenceDto(val online: List<String>? = null)

/** [history] 为 null 表示网关不返回请求记录（旧版本）。 */
data class FriendSnapshot(
    val friends: List<Friend>,
    val pending: List<FriendRequest>,
    val history: List<FriendRequest>? = null,
)

/** 已登录用户的 REST 接口：每个请求带 `uid` + `token`（网关 `authenticateRequest`）。 */
class GoImUserApi(
    private val client: HttpClient,
    private val baseUrl: () -> String,
    private val user: User,
) {
    suspend fun createGroup(name: String, members: List<String>): Group =
        post("group/create", "name" to name, "members" to members.joinToString(",").ifEmpty { null })
            .decode<GroupDto>().toDomain()

    suspend fun joinGroup(groupId: String) {
        post("group/join", "group_id" to groupId)
    }

    suspend fun leaveGroup(groupId: String) {
        post("group/leave", "group_id" to groupId)
    }

    suspend fun inviteToGroup(groupId: String, targetUid: String) {
        post("group/invite", "group_id" to groupId, "target_uid" to targetUid)
    }

    suspend fun listGroups(): List<Group> =
        get("group/list").decode<GroupListDto>().groups.orEmpty().map { it.toDomain() }

    /** 成员按入群顺序；`alias` 为成员自己设置的群昵称。`role` 由调用方按群主补齐。 */
    suspend fun groupMembers(groupId: String): List<GroupMember> {
        val dto = get("group/members", "group_id" to groupId).decode<GroupMembersDto>()
        val aliases = dto.aliases.orEmpty()
        return dto.members.orEmpty().map { GroupMember(groupId, it, alias = aliases[it].orEmpty()) }
    }

    /** 仅群主（服务端校验）。 */
    suspend fun renameGroup(groupId: String, name: String) {
        post("group/rename", "group_id" to groupId, "name" to name)
    }

    /** 设置自己的群昵称，空串清除。 */
    suspend fun setGroupAlias(groupId: String, alias: String) {
        post("group/alias", "group_id" to groupId, "alias" to alias)
    }

    /** 服务端把 `uid` 规整为对端、`friend_uid` 为自己（同 iOS 解析）。 */
    suspend fun friendList(): FriendSnapshot {
        val dto = get("friend/list").decode<FriendListDto>()
        val friends = dto.friends.orEmpty().mapNotNull { row ->
            val peer = row.uid?.takeIf { it.isNotEmpty() } ?: row.friendUid
            peer.takeIf { it.isNotEmpty() && it != user.uid }?.let {
                Friend(
                    it,
                    nickname = row.nickname.orEmpty().ifEmpty { it },
                    avatarUrl = absoluteUrl(baseUrl(), row.avatarUrl),
                    signature = row.signature.orEmpty(),
                    remark = row.remark.orEmpty(),
                )
            }
        }.distinctBy { it.uid }
        val pending = dto.pendingRequests.orEmpty().map {
            FriendRequest(
                fromUid = it.fromUid,
                toUid = user.uid,
                createdAt = it.createdAt ?: 0,
                nickname = it.nickname ?: it.username,
                avatarUrl = absoluteUrl(baseUrl(), it.avatarUrl),
            )
        }
        val history = dto.requestHistory?.map {
            val peer = if (it.fromUid == user.uid) it.toUid else it.fromUid
            FriendRequest(
                fromUid = it.fromUid,
                toUid = it.toUid,
                status = when (it.status) {
                    1 -> FriendRequestStatus.ACCEPTED
                    2 -> FriendRequestStatus.REJECTED
                    else -> FriendRequestStatus.PENDING
                },
                createdAt = it.createdAt,
                nickname = it.nickname?.takeIf { name -> name.isNotEmpty() && name != peer },
                avatarUrl = absoluteUrl(baseUrl(), it.avatarUrl),
            )
        }
        return FriendSnapshot(friends, pending, history)
    }

    suspend fun sendFriendRequest(toUid: String) {
        post("friend/request", "to_uid" to toUid)
    }

    suspend fun respondFriendRequest(fromUid: String, accept: Boolean) {
        post(if (accept) "friend/accept" else "friend/reject", "from_uid" to fromUid)
    }

    suspend fun removeFriend(friendUid: String) {
        post("friend/remove", "friend_uid" to friendUid)
    }

    /** 空串清除备注。 */
    suspend fun setFriendRemark(friendUid: String, remark: String) {
        post("friend/remark", "friend_uid" to friendUid, "remark" to remark)
    }

    /** 自己的完整资料（含邮箱）。 */
    suspend fun myProfile(): UserProfile = get("user/profile").decode<ProfileDto>().toDomain()

    /** 批量公开资料；不存在的用户不返回。 */
    suspend fun profiles(uids: List<String>): List<UserProfile> =
        get("user/profiles", "uids" to uids.joinToString(",")).decode<ProfileListDto>().users.orEmpty().map { it.toDomain() }

    /** [uids] 中当前在线（任一网关有连接）的用户，最多 500 个。 */
    suspend fun presence(uids: Collection<String>): Set<String> =
        get("presence", "uids" to uids.joinToString(",")).decode<PresenceDto>().online.orEmpty().toSet()

    /** 参数为 null 表示不修改；[signature] 为空串清除签名，[avatarFileId] 为 "0" 清除头像。 */
    suspend fun updateProfile(nickname: String? = null, avatarFileId: String? = null, signature: String? = null): UserProfile =
        post("user/profile", "nickname" to nickname, "avatar_file_id" to avatarFileId, "signature" to signature)
            .decode<ProfileDto>().toDomain()

    /** 按昵称（包含）、邮箱或用户 ID（精确）搜索用户。 */
    suspend fun searchUsers(query: String, limit: Int = 20): List<UserProfile> =
        get("user/search", "q" to query, "limit" to limit.toString()).decode<ProfileListDto>().users.orEmpty().map { it.toDomain() }

    suspend fun sendBindEmailCode(email: String) {
        post("auth/email-code", "email" to email, "purpose" to EmailCodePurpose.BIND_EMAIL.raw)
    }

    suspend fun bindEmail(email: String, code: String): UserProfile =
        post("user/email", "email" to email, "code" to code).decode<ProfileDto>().toDomain()

    private fun ProfileDto.toDomain() = UserProfile(
        uid = uid,
        nickname = nickname.ifEmpty { uid },
        avatarUrl = absoluteUrl(baseUrl(), avatarUrl),
        email = email.orEmpty(),
        signature = signature.orEmpty(),
    )

    suspend fun search(query: String, limit: Int): List<SearchHit> =
        get("search", "q" to query, "limit" to limit.toString()).decode<SearchResponseDto>().messages.orEmpty().map { m ->
            val chatType = ChatType.from(m.chatType)
            val peer = if (chatType == ChatType.GROUP) m.to else if (m.from == user.uid) m.to else m.from
            SearchHit(
                serverMsgId = (m.msgId as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toLongOrNull() } ?: 0,
                conversationId = ConversationId.of(user.uid, peer, chatType),
                chatType = chatType,
                peerOrGroupId = peer,
                fromUid = m.from,
                content = m.content,
                timestampMs = m.timestamp,
            )
        }

    // region 管理后台（网关要求 token 的 role 为 admin，否则 403）

    suspend fun adminStats(): AdminStats = get("admin/stats").decode<AdminStatsDto>().toDomain()

    suspend fun adminUsers(offset: Int, limit: Int): AdminUserPage {
        val dto = get("admin/users", "offset" to offset.toString(), "limit" to limit.toString()).decode<AdminUsersDto>()
        return AdminUserPage(dto.users.orEmpty().map { it.toDomain(baseUrl()) }, dto.total)
    }

    suspend fun adminSettings(): AdminSettings = get("admin/settings").decode<AdminSettingsDto>().toDomain()

    /** 只提交非 null 的项。 */
    suspend fun adminUpdateSettings(multiDevice: MultiDevicePolicy?, registrationOpen: Boolean?): AdminSettings =
        post("admin/settings", "multi_device" to multiDevice?.raw, "registration_open" to registrationOpen?.toString())
            .decode<AdminSettingsDto>().toDomain()

    /** 免验证码建号：服务端随机初始密码并发到该邮箱，首次登录须改密码。 */
    suspend fun adminCreateUser(email: String): AdminCreatedUser {
        val dto = post("admin/users/create", "email" to email).decode<AdminCreateUserDto>()
        return AdminCreatedUser(dto.user.toDomain(baseUrl()), dto.initialPassword?.takeIf { !dto.mailed && it.isNotEmpty() })
    }

    suspend fun adminDeleteUser(targetUid: String) {
        post("admin/users/delete", "target_uid" to targetUid)
    }

    /** 禁用后该用户立即下线且无法再登录。 */
    suspend fun adminSetUserDisabled(targetUid: String, disabled: Boolean) {
        post("admin/users/disable", "target_uid" to targetUid, "disabled" to disabled.toString())
    }

    /** 按时间倒序；[before] 为 null 时从最新开始。 */
    suspend fun adminMessages(before: Long?, limit: Int): List<AdminMessage> =
        get("admin/messages", "limit" to limit.toString(), *listOfNotNull(before?.let { "before" to it.toString() }).toTypedArray())
            .decode<AdminMessagesDto>().messages.orEmpty().map { it.toDomain() }

    suspend fun adminDeleteMessage(msgId: Long) {
        post("admin/messages/delete", "msg_id" to msgId.toString())
    }

    /** [targetUid] 参与过的会话，按最后消息时间从新到旧。 */
    suspend fun adminConversations(targetUid: String): List<AdminConversation> =
        get("admin/conversations", "target_uid" to targetUid)
            .decode<AdminConversationsDto>().conversations.orEmpty().map { it.toDomain() }

    /** [targetUid] 与 [peer] 的会话消息，从新到旧；群聊时 [peer] 为群 ID。 */
    suspend fun adminConversationMessages(
        targetUid: String,
        peer: String,
        chatType: ChatType,
        before: Long?,
        limit: Int,
    ): AdminChatPage {
        val dto = get(
            "admin/conversations/messages",
            "target_uid" to targetUid,
            "peer" to peer,
            "chat_type" to chatType.value.toString(),
            "limit" to limit.toString(),
            *listOfNotNull(before?.let { "before" to it.toString() }).toTypedArray(),
        ).decode<AdminChatDto>()
        return AdminChatPage(
            messages = dto.messages.orEmpty().map { it.toDomain() },
            senders = dto.users.orEmpty().mapValues { (_, u) -> AdminSender(u.nickname, absoluteUrl(baseUrl(), u.avatarUrl)) },
            title = dto.title.orEmpty(),
        )
    }

    // endregion

    private fun url(path: String) = baseUrl().trimEnd('/') + "/" + path

    private suspend fun get(path: String, vararg params: Pair<String, String>): HttpResponse = request {
        client.get(url(path)) {
            parameter("uid", user.uid)
            parameter("token", user.token)
            params.forEach { (k, v) -> parameter(k, v) }
            timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
        }
    }

    private suspend fun post(path: String, vararg params: Pair<String, String?>): HttpResponse = request {
        client.submitForm(
            url = url(path),
            formParameters = Parameters.build {
                append("uid", user.uid)
                append("token", user.token)
                params.forEach { (k, v) -> if (v != null) append(k, v) }
            },
        ) {
            timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
        }
    }

    private suspend fun request(block: suspend () -> HttpResponse): HttpResponse {
        val response = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DomainError.Network(e.message ?: e::class.simpleName ?: "network error")
        }
        if (!response.status.isSuccess()) {
            throw DomainError.Server(response.status.value, response.bodyAsText().trim())
        }
        return response
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T = try {
        body<T>()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw DomainError.Server(status.value, "响应解析失败: ${e.message}")
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}
