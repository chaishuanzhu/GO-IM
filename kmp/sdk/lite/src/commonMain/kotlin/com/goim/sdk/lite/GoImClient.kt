package com.goim.sdk.lite

import com.goim.kmp.common.PlatformContext
import com.goim.kmp.model.User
import com.goim.kmp.network.GoImApi
import com.goim.kmp.network.createHttpClient
import com.goim.kmp.repository.AuthRepositoryImpl
import com.goim.kmp.repository.ServerSettingsRepositoryImpl
import com.goim.kmp.repository.SessionEndReason
import com.goim.kmp.repository.SessionManager
import com.goim.kmp.repository.SessionState
import com.goim.kmp.storage.AppDirectories
import com.goim.kmp.storage.DevicePreferencesStore
import com.goim.kmp.storage.SessionStore
import com.goim.kmp.storage.createSecureStore
import com.goim.kmp.storage.goimRootDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Lite SDK 入口：创建后 [openSession] 注入宿主身份，再通过 [session] / 返回值使用业务能力。
 *
 * 不包含登录 / 注册 UI；不依赖 Compose。
 */
@GoImSdkPreview
class GoImClient private constructor(
    private val manager: SessionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val session: StateFlow<GoImSession?> = manager.state
        .map { state -> (state as? SessionState.LoggedIn)?.let { GoImSession(it.session) } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** 非主动关闭（踢下线 / 令牌失效等）时通知宿主；宿主应 [closeSession] 后重新 [openSession]。 */
    val sessionEnded: SharedFlow<SessionEndReason> get() = manager.ended

    /** 底层 [SessionManager]，供 Full SDK / 示范 App 复用（账号列表、switchTo 等）。 */
    val sessionManager: SessionManager get() = manager

    suspend fun openSession(identity: GoImIdentity, persistCredentials: Boolean = true): GoImSession {
        require(identity.uid.isNotBlank()) { "uid required" }
        require(identity.token.isNotBlank()) { "token required" }
        val user = User(
            uid = identity.uid,
            nickname = identity.nickname.orEmpty().ifBlank { identity.uid },
            token = identity.token,
            email = identity.email,
            avatarUrl = identity.avatarUrl,
        )
        return GoImSession(manager.openSession(user, persistCredentials))
    }

    suspend fun closeSession(clearCredentials: Boolean = true) {
        manager.closeSession(clearCredentials)
    }

    /** 冷启动恢复本机已保存凭据（仅当此前 [openSession] persistCredentials=true）。 */
    suspend fun restore(): GoImSession? {
        manager.restore()
        return session.value
    }

    /** 切换 IM 网关根地址（须在 [openSession] 前调用）。 */
    fun setServerUrl(url: String) = manager.setApiBaseUrl(url)

    companion object {
        fun create(config: GoImConfig, context: PlatformContext): GoImClient {
            val directories = AppDirectories(goimRootDirectory(context))
            val device = DevicePreferencesStore(directories.deviceJson)
            if (config.deviceId.isNotBlank()) {
                device.update { it.copy(deviceId = config.deviceId) }
            }
            val settings = ServerSettingsRepositoryImpl(device)
            settings.setApiBaseUrl(config.serverUrl)
            settings.setPreferredTransport(config.transport)

            val http = createHttpClient()
            val api = GoImApi(http) { settings.apiBaseUrl.value }
            val auth = AuthRepositoryImpl(api, SessionStore(createSecureStore(context), device))
            return GoImClient(SessionManager(context, directories, http, api, settings, auth))
        }
    }
}
