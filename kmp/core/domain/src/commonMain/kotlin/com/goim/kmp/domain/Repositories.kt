package com.goim.kmp.domain

import com.goim.kmp.model.Appearance
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.ConnectionState
import com.goim.kmp.model.Conversation
import com.goim.kmp.model.FileMeta
import com.goim.kmp.model.Friend
import com.goim.kmp.model.FriendRequest
import com.goim.kmp.model.Group
import com.goim.kmp.model.GroupMember
import com.goim.kmp.model.Message
import com.goim.kmp.model.SearchHit
import com.goim.kmp.model.StickerItem
import com.goim.kmp.model.StickerPack
import com.goim.kmp.model.StickerRef
import com.goim.kmp.model.MessageStatus
import com.goim.kmp.model.TransportKind
import com.goim.kmp.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AuthRepository {
    suspend fun login(uid: String, username: String, password: String): User

    suspend fun register(uid: String, username: String, password: String): User

    fun currentUser(): User?

    suspend fun saveSession(user: User)

    /** 清除当前会话凭据；保留 `Users/{uid}` 本地数据。 */
    suspend fun logout()

    /** 已保存凭据的账号，最近使用在前。 */
    fun savedAccounts(): List<User>

    /** 切到已保存的账号；凭据不存在时返回 null。 */
    fun activate(uid: String): User?

    /** 去登录页添加账号：清活跃账号但保留凭据。 */
    fun deactivate()

    /** 删除某个已保存账号的凭据。 */
    fun forget(uid: String)
}

/** 设备级服务器设置（`Shared/device.json`）。 */
interface ServerSettingsRepository {
    val apiBaseUrl: StateFlow<String>

    val preferredTransport: StateFlow<TransportKind>

    val appearance: StateFlow<Appearance>

    fun setApiBaseUrl(url: String)

    fun setPreferredTransport(kind: TransportKind)

    fun setAppearance(appearance: Appearance)
}

interface ConnectionRepository {
    val state: StateFlow<ConnectionState>

    val inboundEvents: Flow<InboundEvent>

    suspend fun connect(user: User, transport: TransportKind)

    suspend fun disconnect()

    /** 前台 / 网络恢复入口：已登录且未连上时重连。 */
    suspend fun ensureConnected()

    suspend fun send(envelope: OutboundEnvelope)
}

interface MessageRepository {
    /** 会话最近 [limit] 条，时间升序。 */
    fun observeMessages(conversationId: String, limit: Int): Flow<List<Message>>

    suspend fun countMessages(conversationId: String): Int

    suspend fun upsert(message: Message)

    suspend fun markStatus(clientSeq: Long, status: MessageStatus, serverMsgId: Long?)

    /** 文件类消息先落库 `sending`（content 引用本地暂存 ID），上传完成后由 [deliverOutgoingFile] 发出。 */
    suspend fun enqueueOutgoingFile(to: String, chatType: ChatType, meta: FileMeta, from: User): Message

    /** 用上传后的远端元数据改写 content 并经 CmdFile 发出；链路不可用时落为 `failed` 返回。 */
    suspend fun deliverOutgoingFile(message: Message, meta: FileMeta): Message

    suspend fun sendSticker(to: String, chatType: ChatType, sticker: StickerRef, from: User): Message

    /** 先落库 `sending` 再发出；链路不可用时返回 `failed` 状态的消息（不抛出），由气泡重试。 */
    suspend fun sendText(to: String, chatType: ChatType, text: String, from: User): Message

    /** 重发本地失败的外发消息（同 clientSeq / content）。 */
    suspend fun retry(message: Message)

    suspend fun messages(status: MessageStatus): List<Message>

    suspend fun message(id: String): Message?

    suspend fun messageByServerId(serverMsgId: Long): Message?

    /** 会话中时间晚于 [timestampMs] 的本地消息数（搜索跳转时计算窗口）。 */
    suspend fun countNewerThan(conversationId: String, timestampMs: Long): Int

    /** 请求一页服务端历史；完成帧到达时返回本页条数（超时返回 0）。 */
    suspend fun loadHistory(peer: String, before: Long?, limit: Int, chatType: ChatType): Int

    fun completeHistory(delivered: Int)

    /** CmdHistory 进行中时，历史消息不累加未读。 */
    fun isHistoryInFlight(): Boolean

    suspend fun markRead(peer: String, chatType: ChatType)
}

interface ConversationRepository {
    fun observeConversations(): Flow<List<Conversation>>

    suspend fun conversation(id: String): Conversation?

    suspend fun upsertConversation(from: Message, title: String?, incrementUnread: Boolean)

    suspend fun setUnread(conversationId: String, count: Int)

    suspend fun deleteConversation(id: String)

    /** 当前打开的聊天页会话（该会话不累加未读）。 */
    var activeConversationId: String?

    /** 总未读数（底部 Tab 角标）。 */
    fun observeTotalUnread(): Flow<Int>

    /** 没有消息时也建立会话行（建群 / 加群后立即出现在列表）。 */
    suspend fun ensureConversation(conversation: Conversation)
}

/** 群组：写操作走 HTTP（同步、可拿到错误），由服务端广播群系统通知。 */
interface GroupRepository {
    fun observeGroups(): Flow<List<Group>>

    suspend fun group(groupId: String): Group?

    /** 拉取我加入的群并同步会话标题。 */
    suspend fun refresh(): List<Group>

    suspend fun create(name: String, members: List<String>): Group

    suspend fun join(groupId: String): Group?

    suspend fun leave(groupId: String)

    /** 仅群主可邀请（服务端校验）。 */
    suspend fun invite(groupId: String, uid: String)

    suspend fun members(groupId: String): List<GroupMember>
}

interface FriendRepository {
    fun observeFriends(): Flow<List<Friend>>

    /** 发给我的待处理请求。 */
    fun observeRequests(): Flow<List<FriendRequest>>

    suspend fun refresh()

    suspend fun sendRequest(to: String)

    suspend fun respond(fromUid: String, accept: Boolean)
}

interface SearchRepository {
    /** 服务端全文搜索；服务端不可用（未启用 MySQL 等）时退回本地库。 */
    suspend fun search(query: String, limit: Int = 50): SearchResult
}

data class SearchResult(val hits: List<SearchHit>, val isLocalFallback: Boolean = false)

sealed interface FileDownloadEvent {
    data class Progress(val fraction: Float) : FileDownloadEvent

    data class Completed(val path: String) : FileDownloadEvent

    data class Failed(val message: String) : FileDownloadEvent
}

/** 附件：本地暂存 → 上传（带进度）→ 提升为远端 ID；下载带进度并按 fileId 去重（对应 iOS FileRepository + FileDownloadCenter）。 */
interface FileRepository {
    /** 上传进度 0..1，key 为本地暂存 ID（`local:…`）。 */
    val uploadProgress: StateFlow<Map<String, Float>>

    /** 暂存到 `Users/{uid}/tmp`，返回 `local:{uuid}`。 */
    suspend fun stage(bytes: ByteArray, fileName: String): String

    suspend fun stagedBytes(localId: String): ByteArray?

    /** 上传暂存文件；服务端未返回的宽高由调用方补齐。 */
    suspend fun upload(localId: String, fileName: String, mime: String): FileMeta

    /** 上传成功后把暂存文件复制到 `media/` 或 `files/`，以远端 ID 命名，发送方无需再下载。 */
    suspend fun promote(localId: String, remoteFileId: String, mime: String)

    /** 消息 content 已改写为远端 ID 后删除暂存副本。 */
    suspend fun discardStaged(localId: String)

    fun isUploading(localId: String): Boolean

    /** 本地已有文件（暂存 / 已提升 / 已下载）的绝对路径。 */
    fun localPath(fileId: String): String?

    /** 带 uid / token 的 `/file` 地址；本地 ID 返回 null。 */
    fun remoteUrl(fileId: String, thumb: Boolean): String?

    fun observeDownload(fileId: String, suggestedName: String?): Flow<FileDownloadEvent>

    suspend fun ensureLocal(fileId: String, suggestedName: String?): String
}

/** 贴纸：OSS catalog + 每包 pack.json + 本地缓存 + 最近使用（对应 iOS `StickerRepositoryImpl`）。 */
interface StickerRepository {
    suspend fun packs(): List<StickerPack>

    suspend fun stickers(packId: String): List<StickerItem>

    /** 拉取远端 catalog，缺失或升级的包下载 pack.json。 */
    suspend fun syncCatalog()

    /** 贴纸图片字节：本地包文件 → 消息 url → catalog base_url，下载后落盘。 */
    suspend fun imageBytes(ref: StickerRef): ByteArray?

    /** 发送用：补上接收方可下载的 CDN 地址。 */
    suspend fun enrichedRef(item: StickerItem): StickerRef

    suspend fun recordRecent(ref: StickerRef)

    suspend fun recentStickers(limit: Int = 20): List<StickerRef>
}
