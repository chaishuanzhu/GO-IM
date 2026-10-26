package com.goim.kmp.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goim.kmp.common.userMessage
import com.goim.kmp.domain.LoginUseCase
import com.goim.kmp.model.EmailCodePurpose
import com.goim.kmp.model.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [CHANGE_PASSWORD]：登录成功但服务端要求先改密码（自动创建的管理员账号首次登录）。 */
enum class AuthMode { LOGIN, REGISTER, RESET, CHANGE_PASSWORD }

data class LoginUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    /** 登录：邮箱或用户 ID。 */
    val account: String = "",
    /** 注册 / 找回密码：邮箱。 */
    val email: String = "",
    val code: String = "",
    val password: String = "",
    /** 强制改密：再次输入新密码。 */
    val confirmPassword: String = "",
    val isLoading: Boolean = false,
    val isSendingCode: Boolean = false,
    /** 重新发送验证码前的剩余秒数。 */
    val codeCooldown: Int = 0,
    val error: String? = null,
    val info: String? = null,
) {
    val canSendCode: Boolean get() = isValidEmail(email) && !isSendingCode && codeCooldown == 0

    val canSubmit: Boolean
        get() = !isLoading && when (mode) {
            AuthMode.LOGIN -> account.isNotBlank()
            AuthMode.REGISTER, AuthMode.RESET -> isValidEmail(email) && code.length == CODE_LENGTH && password.length >= MIN_PASSWORD
            AuthMode.CHANGE_PASSWORD -> password.length >= MIN_PASSWORD && password == confirmPassword
        }

    companion object {
        const val CODE_LENGTH = 6
        const val MIN_PASSWORD = 6

        fun isValidEmail(value: String): Boolean {
            val v = value.trim()
            val at = v.indexOf('@')
            return at > 0 && at == v.lastIndexOf('@') && v.indexOf('.', at) > at + 1 && !v.endsWith('.') && ' ' !in v
        }
    }
}

class LoginViewModel(
    private val auth: LoginUseCase,
    /** 凭据已保存后回调：挂载会话并进入主页。 */
    private val onLoggedIn: suspend (User) -> Unit,
    private val cooldownSeconds: Int = 60,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()
    private var countdown: Job? = null

    /** 强制改密期间的用户（会话未保存）与登录时输入的初始密码，只留在内存里。 */
    private var pendingUser: User? = null
    private var initialPassword: String = ""

    fun onModeChange(mode: AuthMode) {
        if (mode != AuthMode.CHANGE_PASSWORD) {
            pendingUser = null
            initialPassword = ""
        }
        _state.update {
            // 登录页填的是邮箱时带到注册 / 找回密码
            val email = it.email.ifBlank { it.account.takeIf { a -> '@' in a }.orEmpty() }
            it.copy(mode = mode, email = email, code = "", password = "", confirmPassword = "", error = null, info = null)
        }
    }

    fun onAccountChange(value: String) = _state.update { it.copy(account = value, error = null) }
    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }
    fun onCodeChange(value: String) = _state.update {
        it.copy(code = value.filter(Char::isDigit).take(LoginUiState.CODE_LENGTH), error = null)
    }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onConfirmPasswordChange(value: String) = _state.update { it.copy(confirmPassword = value, error = null) }

    fun sendCode() {
        val s = _state.value
        if (!s.canSendCode) return
        val purpose = if (s.mode == AuthMode.RESET) EmailCodePurpose.RESET_PASSWORD else EmailCodePurpose.REGISTER
        _state.update { it.copy(isSendingCode = true, error = null, info = null) }
        viewModelScope.launch {
            try {
                auth.sendEmailCode(s.email.trim(), purpose)
                _state.update { it.copy(info = "验证码已发送至 ${s.email.trim()}，10 分钟内有效") }
                startCountdown()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage()) }
            } finally {
                _state.update { it.copy(isSendingCode = false) }
            }
        }
    }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) {
            _state.update { it.copy(error = validationError(it)) }
            return
        }
        if (s.mode == AuthMode.CHANGE_PASSWORD && s.password == initialPassword) {
            _state.update { it.copy(error = "新密码不能与初始密码相同") }
            return
        }
        _state.update { it.copy(isLoading = true, error = null, info = null) }
        viewModelScope.launch {
            try {
                when (s.mode) {
                    AuthMode.LOGIN -> {
                        val user = auth.login(s.account.trim(), s.password)
                        if (user.mustChangePassword) {
                            pendingUser = user
                            initialPassword = s.password
                            _state.update {
                                it.copy(mode = AuthMode.CHANGE_PASSWORD, password = "", confirmPassword = "", info = "首次登录，请先设置新密码")
                            }
                        } else {
                            onLoggedIn(user)
                        }
                    }
                    AuthMode.CHANGE_PASSWORD -> {
                        val user = pendingUser ?: error("登录状态已失效，请重新登录")
                        val saved = auth.changeInitialPassword(user, initialPassword, s.password)
                        pendingUser = null
                        initialPassword = ""
                        onLoggedIn(saved)
                    }
                    AuthMode.REGISTER -> onLoggedIn(auth.register(s.email.trim(), s.code, s.password))
                    AuthMode.RESET -> {
                        auth.resetPassword(s.email.trim(), s.code, s.password)
                        _state.update {
                            it.copy(mode = AuthMode.LOGIN, account = s.email.trim(), password = "", code = "", info = "密码已重置，请用新密码登录")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage()) }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun validationError(s: LoginUiState): String? = when {
        s.isLoading -> null
        s.mode == AuthMode.LOGIN -> "请输入邮箱或用户 ID"
        s.mode == AuthMode.CHANGE_PASSWORD && s.password.length < LoginUiState.MIN_PASSWORD -> "密码至少 ${LoginUiState.MIN_PASSWORD} 位"
        s.mode == AuthMode.CHANGE_PASSWORD -> "两次输入的密码不一致"
        !LoginUiState.isValidEmail(s.email) -> "请输入正确的邮箱"
        s.code.length != LoginUiState.CODE_LENGTH -> "请输入 6 位验证码"
        else -> "密码至少 ${LoginUiState.MIN_PASSWORD} 位"
    }

    private fun startCountdown() {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            for (left in cooldownSeconds downTo 1) {
                _state.update { it.copy(codeCooldown = left) }
                delay(1_000)
            }
            _state.update { it.copy(codeCooldown = 0) }
        }
    }
}
