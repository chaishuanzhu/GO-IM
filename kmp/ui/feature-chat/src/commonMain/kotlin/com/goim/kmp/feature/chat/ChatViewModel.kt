package com.goim.kmp.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goim.kmp.domain.ConversationRepository
import com.goim.kmp.domain.MarkReadUseCase
import com.goim.kmp.domain.MessageRepository
import com.goim.kmp.domain.SendTextMessageUseCase
import com.goim.kmp.model.ConnectionState
import com.goim.kmp.model.Conversation
import com.goim.kmp.model.Message
import com.goim.kmp.model.MsgType
import com.goim.kmp.model.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isLoadingHistory: Boolean = false,
    val hasMoreHistory: Boolean = true,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val error: String? = null,
    /** 搜索跳转定位的消息：页面滚动到该条并短暂高亮。 */
    val highlightId: String? = null,
)

/** 搜索命中定位信息；本地兜底命中优先用 [localMessageId]。 */
data class MessageFocus(val serverMsgId: Long, val localMessageId: String?, val timestampMs: Long)

/** 对齐 iOS `ChatViewModel`：打开即标记活跃 + 已读 + 拉一页历史；上滑加载更早。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    val conversation: Conversation,
    private val self: User,
    private val messages: MessageRepository,
    private val conversations: ConversationRepository,
    private val sendText: SendTextMessageUseCase,
    private val markRead: MarkReadUseCase,
    private val connectionState: StateFlow<ConnectionState>,
    private val focus: MessageFocus? = null,
    mediaDeps: ChatMediaDeps? = null,
) : ViewModel() {

    private val window = MutableStateFlow(INITIAL_WINDOW)
    private val loading = MutableStateFlow(false)
    private val hasMore = MutableStateFlow(true)
    private val error = MutableStateFlow<String?>(null)
    private val highlight = MutableStateFlow<String?>(null)
    private var lastSeenIncomingId: String? = null

    /** 自己消息旁的头像名。 */
    val selfName: String get() = self.username.ifEmpty { self.uid }

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    /** 附件 / 贴纸发送；未注入时（测试）只支持文本。 */
    val media: ChatMediaActions? = mediaDeps?.let { deps ->
        ChatMediaActions(deps, viewModelScope, conversation.peerOrGroupId, conversation.chatType, conversation.title, self) {
            error.value = it
        }
    }

    private val list: StateFlow<List<Message>> = window
        .flatMapLatest { messages.observeMessages(conversation.id, it) }
        .onEach(::onListChanged)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val state: StateFlow<ChatUiState> = combine(
        list,
        loading,
        hasMore,
        connectionState,
        combine(error, highlight, ::Pair),
    ) { msgs, l, more, conn, (err, focused) ->
        ChatUiState(msgs, l, more, conn, err, focused)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState(connectionState = connectionState.value))

    init {
        // 先标记活跃，再拉历史：历史条目不累加未读。
        conversations.activeConversationId = conversation.id
        viewModelScope.launch {
            runCatching { markRead(conversation.id, conversation.peerOrGroupId, conversation.chatType) }
            fetchHistory(before = null)
            focus?.let { focusOn(it) }
        }
    }

    fun onDraftChange(value: String) = _draft.update { value }

    fun send() {
        val text = _draft.value.trim()
        if (text.isEmpty()) return
        _draft.value = ""
        viewModelScope.launch {
            try {
                sendText(conversation.peerOrGroupId, conversation.chatType, text, self, conversation.title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message
            }
        }
    }

    fun retry(message: Message) {
        val actions = media
        if (actions != null && message.msgType != MsgType.Text && message.msgType != MsgType.Sticker) {
            actions.retry(message)
            return
        }
        viewModelScope.launch { runCatching { messages.retry(message) } }
    }

    /** 插入 `@uid `（同 iOS：纯文本提及）。 */
    fun insertMention(uid: String) {
        _draft.update { current ->
            val base = if (current.endsWith("@")) current.dropLast(1) else current
            val sep = if (base.isEmpty() || base.endsWith(" ")) "" else " "
            "$base$sep@$uid "
        }
    }

    fun appendToDraft(text: String) = _draft.update { it + text }

    fun reportError(message: String) {
        error.value = message
    }

    fun loadOlder() {
        if (loading.value || !hasMore.value) return
        val before = list.value.firstOrNull()?.timestampMs
        window.update { it + PAGE_SIZE }
        viewModelScope.launch { fetchHistory(before) }
    }

    fun consumeError() {
        error.value = null
    }

    fun clearHighlight() {
        highlight.value = null
    }

    override fun onCleared() {
        if (conversations.activeConversationId == conversation.id) conversations.activeConversationId = null
    }

    private suspend fun fetchHistory(before: Long?) {
        if (loading.value) return
        loading.value = true
        try {
            val delivered = runCatching {
                messages.loadHistory(conversation.peerOrGroupId, before, PAGE_SIZE, conversation.chatType)
            }.getOrDefault(0)
            val localCount = runCatching { messages.countMessages(conversation.id) }.getOrDefault(0)
            // 离线时无法判断服务端是否还有更早记录，保持可加载。
            val offline = connectionState.value != ConnectionState.CONNECTED
            hasMore.value = offline || delivered >= PAGE_SIZE || localCount > window.value
        } finally {
            loading.value = false
        }
    }

    /** 定位到搜索命中：本地没有就从命中时间点向前拉一页历史，再扩大窗口使其进入列表。 */
    private suspend fun focusOn(target: MessageFocus) {
        val found = locate(target) ?: run {
            runCatching {
                messages.loadHistory(conversation.peerOrGroupId, target.timestampMs + 1, PAGE_SIZE, conversation.chatType)
            }
            locate(target)
        }
        if (found == null) {
            error.value = "未能定位到该消息"
            return
        }
        val newer = runCatching { messages.countNewerThan(conversation.id, found.timestampMs) }.getOrDefault(0)
        window.update { maxOf(it, newer + PAGE_SIZE) }
        highlight.value = found.id
    }

    private suspend fun locate(target: MessageFocus): Message? = runCatching {
        val byLocal = target.localMessageId?.let { messages.message(it) }
        val found = byLocal ?: target.serverMsgId.takeIf { it > 0 }?.let { messages.messageByServerId(it) }
        found?.takeIf { it.conversationId == conversation.id }
    }.getOrNull()

    /** 打开期间保持未读为 0；收到对方新消息时补发已读回执。 */
    private suspend fun onListChanged(list: List<Message>) {
        runCatching { conversations.setUnread(conversation.id, 0) }
        val newestIncoming = list.lastOrNull { !it.isOutgoing } ?: return
        if (lastSeenIncomingId != null && newestIncoming.id != lastSeenIncomingId) {
            runCatching { messages.markRead(conversation.peerOrGroupId, conversation.chatType) }
        }
        lastSeenIncomingId = newestIncoming.id
    }

    companion object {
        const val PAGE_SIZE = 30
        const val INITIAL_WINDOW = 50
    }
}
