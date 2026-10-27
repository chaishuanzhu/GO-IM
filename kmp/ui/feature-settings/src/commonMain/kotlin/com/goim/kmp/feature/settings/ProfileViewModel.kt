package com.goim.kmp.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goim.kmp.common.DomainError
import com.goim.kmp.common.userMessage
import com.goim.kmp.domain.ProfileRepository
import com.goim.kmp.model.UserProfile
import com.goim.kmp.platform.EncodedImage
import com.goim.kmp.platform.ImageCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val imageDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val normalize: (ByteArray) -> EncodedImage? = { ImageCodec.encodeJpeg(it, CROP_SOURCE_MAX, 0.9f) },
    private val crop: (ByteArray, CropSquare) -> ByteArray? = { bytes, sq ->
        ImageCodec.cropSquareJpeg(bytes, sq.left, sq.top, sq.size, AVATAR_SIZE, 0.9f)?.bytes
    },
) : ViewModel() {
    val profile: StateFlow<UserProfile> = profiles.me

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 待裁剪的头像（已摆正、缩到 [CROP_SOURCE_MAX] 内）；非 null 时显示裁剪界面。 */
    private val _cropSource = MutableStateFlow<EncodedImage?>(null)
    val cropSource: StateFlow<EncodedImage?> = _cropSource.asStateFlow()

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

    /** 选图后先解码摆正，再进入裁剪。 */
    fun pickAvatar(bytes: ByteArray) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val image = withContext(imageDispatcher) { normalize(bytes) }
                if (image == null) _message.value = "无法读取这张图片" else _cropSource.value = image
            } finally {
                _busy.value = false
            }
        }
    }

    fun cancelCrop() {
        _cropSource.value = null
    }

    fun confirmCrop(square: CropSquare) {
        val source = _cropSource.value ?: return
        _cropSource.value = null
        run("头像已更新") {
            val bytes = withContext(imageDispatcher) { crop(source.bytes, square) }
                ?: throw DomainError.InvalidState("裁剪失败，请换一张图片")
            profiles.updateAvatar(bytes, "avatar.jpg", "image/jpeg")
        }
    }

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
        /** 头像输出边长（像素）。 */
        const val AVATAR_SIZE = 512
        private const val CROP_SOURCE_MAX = 2048
    }
}
