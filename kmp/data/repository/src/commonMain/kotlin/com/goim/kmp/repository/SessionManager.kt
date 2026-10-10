package com.goim.kmp.repository

import co.touchlab.kermit.Logger
import com.goim.kmp.common.PlatformContext
import com.goim.kmp.database.AppDatabase
import com.goim.kmp.database.buildDatabase
import com.goim.kmp.database.databaseBuilder
import com.goim.kmp.database.exportSnapshot
import com.goim.kmp.database.tableCounts
import com.goim.kmp.common.ioDispatcher
import com.goim.kmp.common.nowMillis
import com.goim.kmp.storage.SystemFileSystem
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import com.goim.kmp.domain.AuthRepository
import com.goim.kmp.domain.ServerSettingsRepository
import com.goim.kmp.model.TransportKind
import com.goim.kmp.model.User
import com.goim.kmp.network.GoImApi
import com.goim.kmp.network.GoImFileApi
import com.goim.kmp.network.StickerCdnApi
import com.goim.kmp.network.GoImUserApi
import com.goim.kmp.storage.AppDirectories
import com.goim.kmp.storage.LocalMediaStore
import com.goim.kmp.storage.UserHome
import com.goim.kmp.transport.IMEndpoint
import com.goim.kmp.transport.IMTransport
import com.goim.kmp.transport.TcpTransport
import com.goim.kmp.transport.WebSocketTransport
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 已登录用户的数据面：独立数据库 + 长连接 + 仓库；登出时整体关闭。 */
class UserSession internal constructor(
    val user: User,
    val home: UserHome,
    private val database: AppDatabase,
    val connection: ConnectionRepositoryImpl,
    val messages: MessageRepositoryImpl,
    val conversations: ConversationRepositoryImpl,
    val groups: GroupRepositoryImpl,
    val friends: FriendRepositoryImpl,
    val search: SearchRepositoryImpl,
    val files: FileRepositoryImpl,
    val stickers: StickerRepositoryImpl,
    private val scope: CoroutineScope,
    private val coordinator: SessionCoordinator,
) {
    internal fun start(transport: TransportKind) {
        coordinator.start()
        scope.launch { runCatching { connection.connect(user, transport) } }
    }

    /** 开发者工具：各表行数。 */
    suspend fun databaseStats(): List<Pair<String, Long>> = database.tableCounts()

    /** 开发者工具：导出数据库快照到 `tmp/`，返回文件路径；调用方用完后删除。 */
    suspend fun exportDatabase(): String = withContext(ioDispatcher) {
        val target = home.tmp / "goim-${user.uid}-${nowMillis()}.sqlite"
        SystemFileSystem.delete(target, mustExist = false)
        database.exportSnapshot(target.toString())
        target.toString()
    }

    fun deleteExport(path: String) {
        runCatching { SystemFileSystem.delete(path.toPath(), mustExist = false) }
    }

    /** 开发者工具：media / files / tmp 占用字节数。 */
    suspend fun storageUsage(): List<Pair<String, Long>> = withContext(ioDispatcher) {
        listOf("media" to home.media, "files" to home.files, "tmp" to home.tmp).map { (name, dir) ->
            name to runCatching {
                SystemFileSystem.listRecursively(dir).sumOf { SystemFileSystem.metadataOrNull(it)?.size ?: 0L }
            }.getOrDefault(0L)
        }
    }

    internal suspend fun close() {
        runCatching { connection.disconnect() }
        runCatching { messages.cancelPendingAcks() }
        scope.cancel()
        database.close()
    }
}

sealed interface SessionState {
    data object LoggedOut : SessionState
    data class LoggedIn(val session: UserSession) : SessionState
}

/** 会话挂载 / 卸载（对齐 iOS `CompositionRoot.mountSession / tearDownMountedSession`）。 */
class SessionManager(
    private val context: PlatformContext,
    private val directories: AppDirectories,
    private val httpClient: HttpClient,
    private val api: GoImApi,
    private val settings: ServerSettingsRepository,
    private val auth: AuthRepository,
) {
    private val log = Logger.withTag("Session")
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    private val _state = MutableStateFlow<SessionState>(SessionState.LoggedOut)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _ended = MutableSharedFlow<SessionEndReason>(extraBufferCapacity = 4)

    /** 非主动登出（被踢 / 登录失效）时提示用户。 */
    val ended: SharedFlow<SessionEndReason> = _ended.asSharedFlow()

    val currentSession: UserSession? get() = (state.value as? SessionState.LoggedIn)?.session

    private val _accounts = MutableStateFlow(auth.savedAccounts())

    /** 本机已保存凭据的账号（最近使用在前），供切换账号使用。 */
    val accounts: StateFlow<List<User>> = _accounts.asStateFlow()

    /** 冷启动：有活跃会话则直接挂载并连接。 */
    suspend fun restore() {
        auth.currentUser()?.let { mount(it) }
    }

    /** 登录 / 注册成功（凭据已保存）后调用。 */
    suspend fun mount(user: User) = lock.withLock {
        (state.value as? SessionState.LoggedIn)?.session?.close()
        val session = createSession(user)
        _state.value = SessionState.LoggedIn(session)
        _accounts.value = auth.savedAccounts()
        session.start(settings.preferredTransport.value)
        log.i { "session mounted uid=${user.uid}" }
    }

    /** 切到已保存的账号：关闭当前会话（DB、长连接）后挂载目标账号，无需重新登录。 */
    suspend fun switchTo(uid: String): Boolean {
        if (currentSession?.user?.uid == uid) return true
        val user = auth.activate(uid) ?: run {
            _accounts.value = auth.savedAccounts()
            return false
        }
        mount(user)
        return true
    }

    /** 回登录页添加账号：卸载当前会话，保留其凭据。 */
    suspend fun addAccount() = lock.withLock {
        val session = currentSession ?: return@withLock
        _state.value = SessionState.LoggedOut
        session.close()
        auth.deactivate()
        _accounts.value = auth.savedAccounts()
    }

    /** 从本机移除某个非当前账号的凭据（本地数据保留）。 */
    fun forget(uid: String) {
        if (currentSession?.user?.uid == uid) return
        auth.forget(uid)
        _accounts.value = auth.savedAccounts()
    }

    suspend fun logout(reason: SessionEndReason = SessionEndReason.LOGOUT) {
        lock.withLock {
            val session = (state.value as? SessionState.LoggedIn)?.session ?: return
            _state.value = SessionState.LoggedOut
            session.close()
            auth.logout()
            _accounts.value = auth.savedAccounts()
        }
        if (reason != SessionEndReason.LOGOUT) _ended.tryEmit(reason)
    }

    private fun createSession(user: User): UserSession {
        val home = directories.userHome(user.uid).also { it.ensureDirectories() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val database = buildDatabase(databaseBuilder(context, home.databaseFile.toString()))
        val connection = ConnectionRepositoryImpl(
            settings = settings,
            scope = scope,
            transportFactory = ::createTransport,
            validateToken = { api.validateToken(it.uid, it.token) },
        )
        val messages = MessageRepositoryImpl(database, connection, scope)
        val conversations = ConversationRepositoryImpl(database)
        val userApi = GoImUserApi(httpClient, { settings.apiBaseUrl.value }, user)
        val groups = GroupRepositoryImpl(userApi, database, conversations)
        val friends = FriendRepositoryImpl(userApi, database, connection, user.uid)
        val search = SearchRepositoryImpl(userApi, database, user.uid)
        val files = FileRepositoryImpl(
            GoImFileApi(httpClient, { settings.apiBaseUrl.value }, user),
            LocalMediaStore(home),
            scope,
        )
        val stickers = StickerRepositoryImpl(StickerCdnApi(httpClient), directories.root / "Catalog" / "Stickers", database)
        val coordinator = SessionCoordinator(user, connection, messages, conversations, scope, groups, friends, files) { reason ->
            appScope.launch { logout(reason) }
        }
        return UserSession(
            user, home, database, connection, messages, conversations, groups, friends, search, files, stickers,
            scope, coordinator,
        )
    }

    private fun createTransport(kind: TransportKind, endpoint: IMEndpoint): IMTransport = when (kind) {
        TransportKind.WEB_SOCKET -> WebSocketTransport(endpoint, httpClient)
        TransportKind.TCP -> TcpTransport(endpoint)
    }
}
