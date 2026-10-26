package com.goim.kmp.domain

import com.goim.kmp.common.DomainError
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.EmailCodePurpose
import com.goim.kmp.model.FileMeta
import com.goim.kmp.model.LocalFileId
import com.goim.kmp.model.MessagePreview
import com.goim.kmp.model.MessageStatus
import com.goim.kmp.model.MsgType
import com.goim.kmp.model.StickerRef
import com.goim.kmp.model.Message
import com.goim.kmp.model.TransportKind
import com.goim.kmp.model.User
import kotlinx.coroutines.CancellationException

/** 登录 / 注册 / 找回密码；连接在会话挂载（UserHome + 数据库）之后由 app 层发起。 */
class LoginUseCase(private val auth: AuthRepository) {
    /**
     * 返回的用户 [User.mustChangePassword] 为 true 时不保存会话：调用方须先 [changeInitialPassword]，
     * 这样中途退出应用也不会带着未改的初始密码进入主页。
     */
    suspend fun login(account: String, password: String): User = auth.login(account, password).also {
        if (!it.mustChangePassword) auth.saveSession(it)
    }

    /** 首次登录强制改密：成功后保存会话并返回可挂载的用户。 */
    suspend fun changeInitialPassword(user: User, oldPassword: String, newPassword: String): User {
        auth.changePassword(user, oldPassword, newPassword)
        return user.copy(mustChangePassword = false).also { auth.saveSession(it) }
    }

    suspend fun register(email: String, code: String, password: String): User =
        auth.register(email, code, password).also { auth.saveSession(it) }

    suspend fun sendEmailCode(email: String, purpose: EmailCodePurpose) = auth.sendEmailCode(email, purpose)

    suspend fun resetPassword(email: String, code: String, newPassword: String) = auth.resetPassword(email, code, newPassword)
}

class LogoutUseCase(
    private val auth: AuthRepository,
    private val connection: ConnectionRepository,
) {
    suspend operator fun invoke() {
        connection.disconnect()
        auth.logout()
    }
}

class SendTextMessageUseCase(
    private val messages: MessageRepository,
    private val conversations: ConversationRepository,
) {
    suspend operator fun invoke(to: String, chatType: ChatType, text: String, from: User, title: String?): Message {
        val sent = messages.sendText(to, chatType, text, from)
        conversations.upsertConversation(sent, title, incrementUnread = false)
        return sent
    }
}

class MarkReadUseCase(
    private val messages: MessageRepository,
    private val conversations: ConversationRepository,
) {
    suspend operator fun invoke(conversationId: String, peer: String, chatType: ChatType) {
        conversations.setUnread(conversationId, 0)
        messages.markRead(peer, chatType)
    }
}

class SwitchTransportUseCase(
    private val auth: AuthRepository,
    private val settings: ServerSettingsRepository,
    private val connection: ConnectionRepository,
) {
    suspend operator fun invoke(kind: TransportKind) {
        settings.setPreferredTransport(kind)
        val user = auth.currentUser() ?: return
        connection.disconnect()
        connection.connect(user, kind)
    }
}

/** 本地尺寸 / 时长：服务端未识别时（如非图片、AAC 被识别为 video/mp4）用客户端值补齐。 */
data class LocalMediaInfo(val width: Int? = null, val height: Int? = null, val duration: Int? = null)

/** 对齐 iOS `SendFileMessageUseCase`：暂存 → 立即显示 sending 气泡 → 上传 → CmdFile → 暂存提升为远端文件。 */
class SendFileMessageUseCase(
    private val files: FileRepository,
    private val messages: MessageRepository,
    private val conversations: ConversationRepository,
) {
    suspend operator fun invoke(
        to: String,
        chatType: ChatType,
        bytes: ByteArray,
        fileName: String,
        mime: String,
        from: User,
        title: String?,
        local: LocalMediaInfo = LocalMediaInfo(),
        voice: Boolean = false,
    ): Message = upload(enqueue(to, chatType, bytes, fileName, mime, from, title, voice) { local })

    /**
     * 暂存并落库 sending 气泡（不上传）。多选图片时先按顺序全部入队再并发 [upload]，保证消息顺序。
     * [probe] 拿到暂存文件路径，可读取视频 / 语音时长等本地信息。
     * [voice] 仅录音为 true；选择的音频文件按普通文件发送。
     */
    suspend fun enqueue(
        to: String,
        chatType: ChatType,
        bytes: ByteArray,
        fileName: String,
        mime: String,
        from: User,
        title: String?,
        voice: Boolean = false,
        probe: (stagedPath: String?) -> LocalMediaInfo = { LocalMediaInfo() },
    ): Message {
        if (bytes.isEmpty()) throw DomainError.InvalidState("文件为空")
        if (bytes.size > MAX_UPLOAD_BYTES) throw DomainError.InvalidState("文件超过 ${MAX_UPLOAD_BYTES / 1024 / 1024}MB，无法发送")
        val localId = files.stage(bytes, fileName)
        val local = runCatching { probe(files.localPath(localId)) }.getOrDefault(LocalMediaInfo())
        val placeholder = FileMeta(localId, fileName, bytes.size.toLong(), mime, local.width, local.height, duration = local.duration)
        val type = if (voice) MsgType.Voice else MsgType.fromMime(mime)
        val pending = messages.enqueueOutgoingFile(to, chatType, type, placeholder, from)
        conversations.upsertConversation(pending, title, incrementUnread = false)
        return pending
    }

    /** 上传 [enqueue] 返回的消息并发出。 */
    suspend fun upload(pending: Message): Message {
        val meta = MessagePreview.decodeFileMeta(pending.content)
            ?: throw DomainError.InvalidState("附件信息缺失")
        return uploadAndDeliver(pending, meta.fileId, meta)
    }

    /** 失败的文件消息：仍在本地暂存则重新上传，否则按原 content 重发。 */
    suspend fun retry(message: Message): Message {
        if (!message.isOutgoing) throw DomainError.InvalidState("only outgoing messages can be retried")
        val meta = MessagePreview.decodeFileMeta(message.content)
        if (meta == null || !LocalFileId.isLocal(meta.fileId)) {
            messages.retry(message)
            return message
        }
        if (files.stagedBytes(meta.fileId) == null) throw DomainError.InvalidState("本地文件已失效，请重新选择发送")
        messages.markStatus(message.clientSeq, MessageStatus.SENDING, null)
        return uploadAndDeliver(message, meta.fileId, meta)
    }

    /**
     * 服务端 `DetectContentType` 常把 AAC/M4A 识别为 `video/mp4` 或 `application/octet-stream`：保留客户端音频类型，
     * 语音才能内置播放；docx 等被识别为 zip / octet-stream 时也以客户端类型为准。
     */
    private fun resolvedMime(local: String, server: String): String {
        val l = local.lowercase()
        val s = server.lowercase()
        return when {
            l.startsWith("audio/") && !s.startsWith("audio/") -> local
            s.isBlank() || s == "application/octet-stream" || s == "application/zip" -> local.ifBlank { server }
            else -> server
        }
    }

    private suspend fun uploadAndDeliver(pending: Message, localId: String, local: FileMeta): Message {
        try {
            val uploaded = files.upload(localId, local.name, local.mime)
            val meta = uploaded.copy(
                mime = resolvedMime(local.mime, uploaded.mime),
                name = uploaded.name.ifBlank { local.name },
                width = uploaded.width?.takeIf { it > 0 } ?: local.width,
                height = uploaded.height?.takeIf { it > 0 } ?: local.height,
                duration = uploaded.duration?.takeIf { it > 0 } ?: local.duration,
            )
            runCatching { files.promote(localId, meta.fileId, meta.mime) }
            val delivered = messages.deliverOutgoingFile(pending, meta)
            // content 已是远端 ID（失败时重发不需要再上传）
            runCatching { files.discardStaged(localId) }
            return delivered
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { messages.markStatus(pending.clientSeq, MessageStatus.FAILED, null) }
            throw e
        }
    }

    companion object {
        /** 服务端默认 `object_storage.max_upload`（直传上限）。 */
        const val MAX_UPLOAD_BYTES = 100 * 1024 * 1024
    }
}

class SendStickerUseCase(
    private val messages: MessageRepository,
    private val conversations: ConversationRepository,
    private val stickers: StickerRepository,
) {
    suspend operator fun invoke(to: String, chatType: ChatType, sticker: StickerRef, from: User, title: String?): Message {
        val sent = messages.sendSticker(to, chatType, sticker, from)
        conversations.upsertConversation(sent, title, incrementUnread = false)
        runCatching { stickers.recordRecent(sticker) }
        return sent
    }
}
