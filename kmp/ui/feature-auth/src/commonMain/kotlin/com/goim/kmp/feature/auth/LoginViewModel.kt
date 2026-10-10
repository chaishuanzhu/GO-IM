package com.goim.kmp.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goim.kmp.domain.LoginUseCase
import com.goim.kmp.model.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val uid: String = "",
    val username: String = "",
    val password: String = "",
    val isRegister: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = uid.isNotBlank() && !isLoading
}

class LoginViewModel(
    private val login: LoginUseCase,
    /** 凭据已保存后回调：挂载会话并进入主页。 */
    private val onLoggedIn: suspend (User) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onUidChange(value: String) = _state.update { it.copy(uid = value, error = null) }
    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onRegisterChange(value: Boolean) = _state.update { it.copy(isRegister = value, error = null) }

    fun submit() {
        val s = _state.value
        val uid = s.uid.trim()
        if (uid.isEmpty()) {
            _state.update { it.copy(error = "请输入用户 ID") }
            return
        }
        if (s.isLoading) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val user = login(uid, s.username.trim().ifEmpty { uid }, s.password, s.isRegister)
                onLoggedIn(user)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "登录失败") }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }
}
