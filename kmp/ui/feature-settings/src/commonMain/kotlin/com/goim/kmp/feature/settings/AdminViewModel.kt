package com.goim.kmp.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goim.kmp.common.userMessage
import com.goim.kmp.model.AdminCreatedUser
import com.goim.kmp.model.AdminMessage
import com.goim.kmp.model.AdminSettings
import com.goim.kmp.model.AdminStats
import com.goim.kmp.model.AdminUser
import com.goim.kmp.model.AdminUserPage
import com.goim.kmp.model.MultiDevicePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 管理后台的数据来源（网关 `/admin` 系列接口）；由 app 层按当前会话装配。 */
class AdminDeps(
    val selfUid: String,
    val stats: suspend () -> AdminStats,
    val users: suspend (offset: Int, limit: Int) -> AdminUserPage,
    val createUser: suspend (email: String) -> AdminCreatedUser,
    val deleteUser: suspend (uid: String) -> Unit,
    val setUserDisabled: suspend (uid: String, disabled: Boolean) -> Unit,
    /** [before] 为 null 时从最新一条开始，按时间倒序。 */
    val messages: suspend (before: Long?, limit: Int) -> List<AdminMessage>,
    val settings: suspend () -> AdminSettings,
    /** 只提交非 null 的项，返回修改后的设置。 */
    val updateSettings: suspend (multiDevice: MultiDevicePolicy?, registrationOpen: Boolean?) -> AdminSettings,
)

/** 分页列表的通用状态；[error] 只在首屏加载失败时用于空状态，翻页失败走 notice。 */
data class AdminPage<T>(
    val items: List<T> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
)

data class AdminUiState(
    val stats: AdminStats? = null,
    val statsLoading: Boolean = false,
    val statsError: String? = null,
    val users: AdminPage<AdminUser> = AdminPage(),
    val usersTotal: Int = 0,
    val messages: AdminPage<AdminMessage> = AdminPage(),
    val settings: AdminSettings? = null,
    val settingsError: String? = null,
    val savingSettings: Boolean = false,
    val creatingUser: Boolean = false,
    /** 刚添加但初始密码邮件没发出去的用户及其密码，弹窗展示给管理员转交。 */
    val unsentPassword: AdminCreatedUser? = null,
    /** 正在提交禁用 / 启用的用户 UID。 */
    val updatingUser: String? = null,
    val notice: String? = null,
)

class AdminViewModel(private val deps: AdminDeps) : ViewModel() {
    private val _state = MutableStateFlow(AdminUiState())
    val state: StateFlow<AdminUiState> = _state.asStateFlow()

    val selfUid: String get() = deps.selfUid

    init {
        refreshStats()
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun refreshStats() {
        if (_state.value.statsLoading) return
        _state.update { it.copy(statsLoading = true) }
        viewModelScope.launch {
            val result = attempt { deps.stats() }
            _state.update { s ->
                result.fold(
                    onSuccess = { s.copy(stats = it, statsLoading = false, statsError = null) },
                    onFailure = { s.copy(statsLoading = false, statsError = it.userMessage()) },
                )
            }
        }
    }

    /** 首次进入「用户」页时调用；已加载过则不重复请求。 */
    fun ensureUsers() {
        if (!_state.value.users.loaded) loadUsers(reset = true)
    }

    fun refreshUsers() = loadUsers(reset = true)

    fun loadMoreUsers() {
        val page = _state.value.users
        if (page.loaded && !page.endReached) loadUsers(reset = false)
    }

    private fun loadUsers(reset: Boolean) {
        val current = _state.value.users
        if (current.loading) return
        _state.update { it.copy(users = current.copy(loading = true)) }
        val offset = if (reset) 0 else current.items.size
        viewModelScope.launch {
            val result = attempt { deps.users(offset, PAGE_SIZE) }
            _state.update { s ->
                result.fold(
                    onSuccess = { page ->
                        val items = (if (reset) page.users else s.users.items + page.users).distinctBy { it.uid }
                        s.copy(
                            users = AdminPage(items, loaded = true, endReached = page.users.isEmpty() || items.size >= page.total),
                            usersTotal = page.total,
                        )
                    },
                    onFailure = { s.failed(s.users, it) { p -> copy(users = p) } },
                )
            }
        }
    }

    fun ensureMessages() {
        if (!_state.value.messages.loaded) loadMessages(reset = true)
    }

    fun refreshMessages() = loadMessages(reset = true)

    fun loadMoreMessages() {
        val page = _state.value.messages
        if (page.loaded && !page.endReached) loadMessages(reset = false)
    }

    private fun loadMessages(reset: Boolean) {
        val current = _state.value.messages
        if (current.loading) return
        _state.update { it.copy(messages = current.copy(loading = true)) }
        val before = if (reset) null else current.items.lastOrNull()?.timestampMs
        viewModelScope.launch {
            val result = attempt { deps.messages(before, PAGE_SIZE) }
            _state.update { s ->
                result.fold(
                    onSuccess = { page ->
                        val items = (if (reset) page else s.messages.items + page).distinctBy { it.msgId }
                        s.copy(messages = AdminPage(items, loaded = true, endReached = page.size < PAGE_SIZE))
                    },
                    onFailure = { s.failed(s.messages, it) { p -> copy(messages = p) } },
                )
            }
        }
    }

    /** 首次进入「设置」页时调用。 */
    fun ensureSettings() {
        if (_state.value.settings == null) refreshSettings()
    }

    fun refreshSettings() {
        viewModelScope.launch {
            val result = attempt { deps.settings() }
            _state.update { s ->
                result.fold(
                    onSuccess = { s.copy(settings = it, settingsError = null) },
                    onFailure = { s.copy(settingsError = it.userMessage()) },
                )
            }
        }
    }

    fun setMultiDevice(policy: MultiDevicePolicy) {
        if (_state.value.settings?.multiDevice != policy) saveSettings(policy, null)
    }

    fun setRegistrationOpen(open: Boolean) {
        if (_state.value.settings?.registrationOpen != open) saveSettings(null, open)
    }

    private fun saveSettings(multiDevice: MultiDevicePolicy?, registrationOpen: Boolean?) {
        if (_state.value.savingSettings) return
        _state.update { it.copy(savingSettings = true) }
        viewModelScope.launch {
            val result = attempt { deps.updateSettings(multiDevice, registrationOpen) }
            _state.update { s ->
                result.fold(
                    onSuccess = { s.copy(settings = it, savingSettings = false, notice = "设置已保存") },
                    onFailure = { e -> s.copy(savingSettings = false, notice = "保存失败：${e.userMessage()}") },
                )
            }
        }
    }

    fun createUser(email: String) {
        if (_state.value.creatingUser) return
        _state.update { it.copy(creatingUser = true) }
        viewModelScope.launch {
            val result = attempt { deps.createUser(email) }
            _state.update { s ->
                result.fold(
                    onSuccess = { created ->
                        s.copy(
                            users = s.users.copy(items = listOf(created.user) + s.users.items.filterNot { it.uid == created.user.uid }),
                            usersTotal = s.usersTotal + 1,
                            stats = s.stats?.let { it.copy(totalUsers = it.totalUsers + 1) },
                            creatingUser = false,
                            unsentPassword = created.takeIf { it.initialPassword != null },
                            notice = if (created.initialPassword == null) "已添加 ${created.user.email}，初始密码已发送到该邮箱" else null,
                        )
                    },
                    onFailure = { e -> s.copy(creatingUser = false, notice = "添加失败：${e.userMessage()}") },
                )
            }
        }
    }

    fun dismissUnsentPassword() = _state.update { it.copy(unsentPassword = null) }

    fun deleteUser(user: AdminUser) {
        if (user.uid == deps.selfUid) return
        viewModelScope.launch {
            attempt { deps.deleteUser(user.uid) }.fold(
                onSuccess = {
                    _state.update { s ->
                        s.copy(
                            users = s.users.copy(items = s.users.items.filterNot { it.uid == user.uid }),
                            usersTotal = (s.usersTotal - 1).coerceAtLeast(0),
                            stats = s.stats?.let { it.copy(totalUsers = (it.totalUsers - 1).coerceAtLeast(0)) },
                            notice = "已删除用户 ${user.displayName}",
                        )
                    }
                },
                onFailure = { e -> _state.update { it.copy(notice = "删除失败：${e.userMessage()}") } },
            )
        }
    }

    fun setUserDisabled(user: AdminUser, disabled: Boolean) {
        if (user.uid == deps.selfUid || _state.value.updatingUser != null) return
        _state.update { it.copy(updatingUser = user.uid) }
        viewModelScope.launch {
            val result = attempt { deps.setUserDisabled(user.uid, disabled) }
            _state.update { s ->
                result.fold(
                    onSuccess = {
                        s.copy(
                            users = s.users.copy(items = s.users.items.map { if (it.uid == user.uid) it.copy(isDisabled = disabled) else it }),
                            updatingUser = null,
                            notice = if (disabled) "已禁用 ${user.displayName}" else "已启用 ${user.displayName}",
                        )
                    },
                    onFailure = { e -> s.copy(updatingUser = null, notice = "操作失败：${e.userMessage()}") },
                )
            }
        }
    }


    private inline fun <T> AdminUiState.failed(
        page: AdminPage<T>,
        error: Throwable,
        set: AdminUiState.(AdminPage<T>) -> AdminUiState,
    ): AdminUiState {
        val message = error.userMessage()
        return if (page.items.isEmpty()) {
            set(page.copy(loading = false, loaded = true, error = message))
        } else {
            set(page.copy(loading = false)).copy(notice = "加载失败：$message")
        }
    }

    private suspend inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
