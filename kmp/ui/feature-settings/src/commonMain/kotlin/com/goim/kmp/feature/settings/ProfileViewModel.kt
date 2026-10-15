package com.goim.kmp.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goim.kmp.common.userMessage
import com.goim.kmp.domain.ProfileRepository
import com.goim.kmp.model.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 绑定邮箱对话框的状态。 */
data class BindEmailState(
    val sending: Boolean = false,
    val cooldown: Int = 0,
    val codeSent: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
)

/** 个人资料页：头像、昵称、邮箱。 */
class ProfileViewModel(
    private val profiles: ProfileRepository,
    private val cooldownSeconds: Int = 60,
) : ViewModel() {
    val profile: StateFlow<UserProfile> = profiles.me

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _bind = MutableStateFlow(BindEmailState())
    val bind: StateFlow<BindEmailState> = _bind.asStateFlow()
    private var countdown: Job? = null

    init {
        viewModelScope.launch { runCatching { profiles.refreshMe() } }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun updateNickname(nickname: String) = run("昵称已更新") { profiles.updateNickname(nickname) }

    fun updateAvatar(bytes: ByteArray, fileName: String) =
        run("头像已更新") { profiles.updateAvatar(bytes, fileName, imageMime(fileName)) }

    fun copied() {
        _message.value = "用户 ID 已复制"
    }

    fun resetBind() {
        _bind.value = BindEmailState(cooldown = _bind.value.cooldown)
    }

    fun sendBindCode(email: String) {
        val s = _bind.value
        if (s.sending || s.cooldown > 0) return
        _bind.value = s.copy(sending = true, error = null)
        viewModelScope.launch {
            try {
                profiles.sendBindEmailCode(email)
                _bind.value = _bind.value.copy(codeSent = true)
                startCountdown()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _bind.value = _bind.value.copy(error = e.userMessage())
            } finally {
                _bind.value = _bind.value.copy(sending = false)
            }
        }
    }

    /** 成功后回调 [onDone] 关闭对话框。 */
    fun bindEmail(email: String, code: String, onDone: () -> Unit) {
        if (_bind.value.submitting) return
        _bind.value = _bind.value.copy(submitting = true, error = null)
        viewModelScope.launch {
            try {
                profiles.bindEmail(email, code)
                _message.value = "邮箱已绑定"
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _bind.value = _bind.value.copy(error = e.userMessage())
            } finally {
                _bind.value = _bind.value.copy(submitting = false)
            }
        }
    }

    private fun run(success: String, block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
                _message.value = success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.userMessage()
            } finally {
                _busy.value = false
            }
        }
    }

    private fun startCountdown() {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            for (left in cooldownSeconds downTo 1) {
                _bind.value = _bind.value.copy(cooldown = left)
                delay(1_000)
            }
            _bind.value = _bind.value.copy(cooldown = 0)
        }
    }

    companion object {
        fun imageMime(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            else -> "image/jpeg"
        }
    }
}
