package com.goim.kmp.network

import com.goim.kmp.common.DomainError
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.ConversationId
import com.goim.kmp.model.EmailCodePurpose
import com.goim.kmp.model.Friend
import com.goim.kmp.model.FriendRequest
import com.goim.kmp.model.Group
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
)

@Serializable
internal data class FriendRowDto(
    val uid: String? = null,
    @SerialName("friend_uid") val friendUid: String = "",
    val nickname: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
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
)

@Serializable
internal data class ProfileListDto(val users: List<ProfileDto>? = null)

@Serializable
internal data class FriendListDto(
    val friends: List<FriendRowDto>? = null,
    @SerialName("pending_requests") val pendingRequests: List<PendingRequestDto>? = null,
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

data class FriendSnapshot(val friends: List<Friend>, val pending: List<FriendRequest>)

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

    suspend fun groupMembers(groupId: String): List<String> =
        get("group/members", "group_id" to groupId).decode<GroupMembersDto>().members.orEmpty()

    /** 服务端把 `uid` 规整为对端、`friend_uid` 为自己（同 iOS 解析）。 */
    suspend fun friendList(): FriendSnapshot {
        val dto = get("friend/list").decode<FriendListDto>()
        val friends = dto.friends.orEmpty().mapNotNull { row ->
            val peer = row.uid?.takeIf { it.isNotEmpty() } ?: row.friendUid
            peer.takeIf { it.isNotEmpty() && it != user.uid }?.let {
                Friend(it, nickname = row.nickname.orEmpty().ifEmpty { it }, avatarUrl = absoluteUrl(baseUrl(), row.avatarUrl))
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
        return FriendSnapshot(friends, pending)
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

    /** 自己的完整资料（含邮箱）。 */
    suspend fun myProfile(): UserProfile = get("user/profile").decode<ProfileDto>().toDomain()

    /** 批量公开资料；不存在的用户不返回。 */
    suspend fun profiles(uids: List<String>): List<UserProfile> =
        get("user/profiles", "uids" to uids.joinToString(",")).decode<ProfileListDto>().users.orEmpty().map { it.toDomain() }

    /** [nickname] / [avatarFileId] 为 null 表示不修改；[avatarFileId] 为 "0" 清除头像。 */
    suspend fun updateProfile(nickname: String? = null, avatarFileId: String? = null): UserProfile =
        post("user/profile", "nickname" to nickname, "avatar_file_id" to avatarFileId).decode<ProfileDto>().toDomain()

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
