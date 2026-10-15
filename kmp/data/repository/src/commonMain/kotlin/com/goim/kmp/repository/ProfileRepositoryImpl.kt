package com.goim.kmp.repository

import co.touchlab.kermit.Logger
import com.goim.kmp.common.DomainError
import com.goim.kmp.common.nowMillis
import com.goim.kmp.database.AppDatabase
import com.goim.kmp.database.ProfileEntity
import com.goim.kmp.database.toDomain
import com.goim.kmp.domain.FileRepository
import com.goim.kmp.domain.ProfileRepository
import com.goim.kmp.model.ConversationId
import com.goim.kmp.model.User
import com.goim.kmp.model.UserProfile
import com.goim.kmp.network.GoImUserApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * 资料缓存：界面按 UID 订阅，缺失或过期的 UID 汇总后批量请求 `/user/profiles`。
 * 拉到昵称后同步单聊会话标题；自己的资料变化时回调 [onSelfChanged]（更新已保存账号）。
 */
class ProfileRepositoryImpl(
    private val api: GoImUserApi,
    database: AppDatabase,
    private val files: FileRepository,
    self: User,
    private val scope: CoroutineScope,
    private val onSelfChanged: (UserProfile) -> Unit = {},
    private val ttlMillis: Long = 10 * 60_000L,
    private val batchWindowMillis: Long = 50,
) : ProfileRepository {
    private val log = Logger.withTag("Profile")
    private val dao = database.profileDao()
    private val conversations = database.conversationDao()
    private val selfUid = self.uid
    private val requests = Channel<String>(Channel.UNLIMITED)

    /** 只在批处理协程内访问：UID → 最近一次请求时间，避免同一 UID 反复请求。 */
    private val lastAttempt = HashMap<String, Long>()

    private val _me = MutableStateFlow(UserProfile(self.uid, self.nickname, self.avatarUrl, self.email))
    override val me: StateFlow<UserProfile> = _me.asStateFlow()

    init {
        scope.launch { pump() }
    }

    override fun observe(uid: String): Flow<UserProfile?> = dao.observe(uid)
        .onEach { row -> if (row == null || nowMillis() - row.fetchedAt > ttlMillis) requests.trySend(uid) }
        .map { row ->
            when {
                uid == selfUid -> _me.value
                else -> row?.toDomain()
            }
        }

    override fun prefetch(uids: Collection<String>) {
        uids.forEach { if (it.isNotEmpty()) requests.trySend(it) }
    }

    override suspend fun refreshMe(): UserProfile = applySelf(api.myProfile())

    override suspend fun updateNickname(nickname: String): UserProfile {
        val trimmed = nickname.trim()
        if (trimmed.isEmpty()) throw DomainError.InvalidState("昵称不能为空")
        if (trimmed.length > MAX_NICKNAME) throw DomainError.InvalidState("昵称最多 $MAX_NICKNAME 个字")
        return applySelf(api.updateProfile(nickname = trimmed))
    }

    override suspend fun updateAvatar(bytes: ByteArray, fileName: String, mime: String): UserProfile {
        if (!mime.startsWith("image/")) throw DomainError.InvalidState("请选择图片")
        if (bytes.size > MAX_AVATAR_BYTES) throw DomainError.InvalidState("图片不能超过 ${MAX_AVATAR_BYTES / 1024 / 1024}MB")
        val localId = files.stage(bytes, fileName)
        try {
            val meta = files.upload(localId, fileName, mime)
            return applySelf(api.updateProfile(avatarFileId = meta.fileId))
        } finally {
            runCatching { files.discardStaged(localId) }
        }
    }

    override suspend fun search(query: String): List<UserProfile> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val users = api.searchUsers(q)
        cache(users)
        return users
    }

    override suspend fun sendBindEmailCode(email: String) = api.sendBindEmailCode(email.trim())

    override suspend fun bindEmail(email: String, code: String): UserProfile = applySelf(api.bindEmail(email.trim(), code.trim()))

    private suspend fun applySelf(profile: UserProfile): UserProfile {
        val merged = profile.copy(email = profile.email.ifEmpty { _me.value.email })
        _me.value = merged
        persist { dao.upsert(listOf(merged.toEntity())) }
        runCatching { onSelfChanged(merged) }
        return merged
    }

    private suspend fun pump() {
        while (true) {
            val batch = linkedSetOf(requests.receive())
            delay(batchWindowMillis)
            while (true) batch += requests.tryReceive().getOrNull() ?: break
            val now = nowMillis()
            val due = batch.filter { uid -> lastAttempt[uid]?.let { now - it > RETRY_MILLIS } ?: true }
            if (due.isEmpty()) continue
            due.forEach { lastAttempt[it] = now }
            due.chunked(MAX_BATCH).forEach { chunk ->
                try {
                    cache(api.profiles(chunk))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w { "fetch ${chunk.size} profiles failed: ${e.message}" }
                    chunk.forEach { lastAttempt.remove(it) }
                }
            }
        }
    }

    private suspend fun cache(users: List<UserProfile>) {
        if (users.isEmpty()) return
        persist {
            dao.upsert(users.map { it.toEntity() })
            users.filter { it.uid != selfUid && it.nickname.isNotBlank() }.forEach {
                conversations.updateTitle(ConversationId.dm(selfUid, it.uid), it.nickname)
            }
        }
    }

    private fun UserProfile.toEntity() = ProfileEntity(uid, nickname, avatarUrl, nowMillis())

    private companion object {
        const val MAX_BATCH = 200
        const val MAX_NICKNAME = 32
        const val MAX_AVATAR_BYTES = 10 * 1024 * 1024
        const val RETRY_MILLIS = 60_000L
    }
}
