package com.goim.kmp.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goim.kmp.domain.ServerSettingsRepository
import com.goim.kmp.model.Appearance
import com.goim.kmp.model.ConnectionState
import com.goim.kmp.model.TransportKind
import com.goim.kmp.model.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val user: User? = null,
    val apiBaseUrl: String = "",
    val transport: TransportKind = TransportKind.WEB_SOCKET,
    val connectionState: ConnectionState? = null,
    val isSwitching: Boolean = false,
    val appearance: Appearance = Appearance.SYSTEM,
    /** 本机已保存的账号（含当前账号）。 */
    val accounts: List<User> = emptyList(),
    val error: String? = null,
)

/** 账号切换相关操作；未登录的「服务器设置」页不需要。 */
class AccountActions(
    val accounts: StateFlow<List<User>>,
    val switchTo: suspend (uid: String) -> Boolean,
    val addAccount: suspend () -> Unit,
    val forget: (uid: String) -> Unit,
)

/**
 * 登录后「设置」与登录前「服务器设置」共用。
 * 未登录时 [user] / [connectionState] / [accountActions] 为 null，切换传输方式只写偏好。
 */
class SettingsViewModel(
    private val user: User?,
    private val settings: ServerSettingsRepository,
    connectionState: StateFlow<ConnectionState>?,
    private val switchTransport: suspend (TransportKind) -> Unit,
    private val logout: suspend () -> Unit = {},
    private val accountActions: AccountActions? = null,
) : ViewModel() {
    private val switching = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    private val connection = combine(
        settings.apiBaseUrl,
        settings.preferredTransport,
        connectionState ?: flowOf(null),
        switching,
    ) { url, transport, conn, isSwitching -> SettingsUiState(user, url, transport, conn, isSwitching) }

    val state: StateFlow<SettingsUiState> = combine(
        connection,
        settings.appearance,
        accountActions?.accounts ?: flowOf(emptyList()),
        error,
    ) { base, appearance, accounts, err ->
        base.copy(appearance = appearance, accounts = accounts, error = err)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SettingsUiState(
            user = user,
            apiBaseUrl = settings.apiBaseUrl.value,
            transport = settings.preferredTransport.value,
            connectionState = connectionState?.value,
            appearance = settings.appearance.value,
            accounts = accountActions?.accounts?.value.orEmpty(),
        ),
    )

    val supportsAccounts: Boolean get() = accountActions != null

    fun setApiBaseUrl(url: String) = settings.setApiBaseUrl(url)

    fun setAppearance(appearance: Appearance) = settings.setAppearance(appearance)

    fun setTransport(kind: TransportKind) {
        if (kind == settings.preferredTransport.value || switching.value) return
        switching.value = true
        viewModelScope.launch {
            try {
                switchTransport(kind)
            } catch (_: Exception) {
                // 连接失败由连接状态展示，这里不打断
            } finally {
                switching.value = false
            }
        }
    }

    /** 切换后整个已登录界面随会话重建，本 ViewModel 也会随之销毁。 */
    fun switchAccount(uid: String) {
        val actions = accountActions ?: return
        if (uid == user?.uid) return
        viewModelScope.launch {
            try {
                if (!actions.switchTo(uid)) error.value = "该账号登录信息已失效，请重新登录"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "切换失败"
            }
        }
    }

    fun addAccount() {
        val actions = accountActions ?: return
        viewModelScope.launch { actions.addAccount() }
    }

    fun forgetAccount(uid: String) {
        accountActions?.forget?.invoke(uid)
    }

    fun consumeError() {
        error.value = null
    }

    fun logout() {
        viewModelScope.launch { logout.invoke() }
    }
}
