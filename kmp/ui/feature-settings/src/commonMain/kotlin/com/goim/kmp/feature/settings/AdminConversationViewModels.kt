package com.goim.kmp.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goim.kmp.common.userMessage
import com.goim.kmp.model.AdminChatPage
import com.goim.kmp.model.AdminConversation
import com.goim.kmp.model.AdminMessage
import com.goim.kmp.model.AdminSender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdminConversationsUiState(
    val items: List<AdminConversation> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

/** 管理后台：某个用户的会话列表（一次取完，服务端最多 200 条）。 */
class AdminConversationsViewModel(private val load: suspend () -> List<AdminConversation>) : ViewModel() {
    private val _state = MutableStateFlow(AdminConversationsUiState())
    val state: StateFlow<AdminConversationsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val result = attempt { load() }
            _state.update { s ->
                result.fold(
                    onSuccess = { AdminConversationsUiState(items = it, loaded = true) },
                    onFailure = { s.copy(loading = false, loaded = true, error = it.userMessage()) },
                )
            }
        }
    }
}

data class AdminChatUiState(
    /** 从新到旧，配合 reverseLayout 的列表直接使用。 */
    val messages: List<AdminMessage> = emptyList(),
    val senders: Map<String, AdminSender> = emptyMap(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

/** 管理后台：只读查看某个会话的消息，向上滚动加载更早；可删除单条消息。 */
class AdminChatViewModel(
    private val load: suspend (before: Long?, limit: Int) -> AdminChatPage,
    private val deleteMessage: suspend (msgId: Long) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(AdminChatUiState())
    val state: StateFlow<AdminChatUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = loadPage(reset = true)

    fun loadOlder() {
        val s = _state.value
        if (s.loaded && !s.endReached) loadPage(reset = false)
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun delete(message: AdminMessage) {
        viewModelScope.launch {
            attempt { deleteMessage(message.msgId) }.fold(
                onSuccess = {
                    _state.update { s -> s.copy(messages = s.messages.filterNot { it.msgId == message.msgId }, notice = "消息已删除") }
                },
                onFailure = { e -> _state.update { it.copy(notice = "删除失败：${e.userMessage()}") } },
            )
        }
    }

    private fun loadPage(reset: Boolean) {
        val current = _state.value
        if (current.loading) return
        _state.update { it.copy(loading = true) }
        val before = if (reset) null else current.messages.lastOrNull()?.timestampMs
        viewModelScope.launch {
            val result = attempt { load(before, PAGE_SIZE) }
            _state.update { s ->
                result.fold(
                    onSuccess = { page ->
                        val messages = (if (reset) page.messages else s.messages + page.messages).distinctBy { it.msgId }
                        s.copy(
                            messages = messages,
                            senders = (if (reset) emptyMap() else s.senders) + page.senders,
                            loading = false,
                            loaded = true,
                            endReached = page.messages.size < PAGE_SIZE,
                            error = null,
                        )
                    },
                    onFailure = { e ->
                        val message = e.userMessage()
                        if (s.messages.isEmpty()) {
                            s.copy(loading = false, loaded = true, error = message)
                        } else {
                            s.copy(loading = false, notice = "加载失败：$message")
                        }
                    },
                )
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}

private suspend inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
