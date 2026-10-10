package com.goim.kmp.feature.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goim.kmp.domain.ConversationRepository
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.ConnectionState
import com.goim.kmp.model.Conversation
import com.goim.kmp.model.ConversationId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConversationListUiState(
    val conversations: List<Conversation> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val loaded: Boolean = false,
)

class ConversationListViewModel(
    private val selfUid: String,
    private val conversations: ConversationRepository,
    connectionState: StateFlow<ConnectionState>,
) : ViewModel() {

    val state: StateFlow<ConversationListUiState> = combine(
        conversations.observeConversations().catch { emit(emptyList()) },
        connectionState,
    ) { list, conn -> ConversationListUiState(list, conn, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationListUiState())

    fun delete(id: String) {
        viewModelScope.launch { runCatching { conversations.deleteConversation(id) } }
    }

    /** 新建单聊：会话行在第一条消息时才落库（同 iOS）。 */
    fun newDirectChat(peerUid: String): Conversation = Conversation(
        id = ConversationId.dm(selfUid, peerUid),
        chatType = ChatType.SINGLE,
        title = peerUid,
        peerOrGroupId = peerUid,
    )
}
