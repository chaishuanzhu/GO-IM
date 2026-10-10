package com.goim.kmp.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goim.kmp.common.userMessage
import com.goim.kmp.domain.ConversationRepository
import com.goim.kmp.domain.SearchRepository
import com.goim.kmp.model.SearchHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchRow(val hit: SearchHit, val conversationTitle: String)

data class SearchUiState(
    val query: String = "",
    val rows: List<SearchRow> = emptyList(),
    val searching: Boolean = false,
    /** 已对当前关键词完成一次搜索（区分"未输入"与"无结果"）。 */
    val searched: Boolean = false,
    val isLocalFallback: Boolean = false,
    val error: String? = null,
)

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val search: SearchRepository,
    private val conversations: ConversationRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.map { it.query.trim() }
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MILLIS)
                .collectLatest(::run)
        }
    }

    fun onQueryChange(value: String) = _state.update { it.copy(query = value) }

    /** 回车立即搜索（跳过防抖）。 */
    fun submit() {
        val q = _state.value.query.trim()
        viewModelScope.launch { run(q) }
    }

    private suspend fun run(query: String) {
        if (query.isEmpty()) {
            _state.update { it.copy(rows = emptyList(), searching = false, searched = false, error = null, isLocalFallback = false) }
            return
        }
        _state.update { it.copy(searching = true, error = null) }
        try {
            val result = search.search(query)
            val titles = mutableMapOf<String, String>()
            val rows = result.hits.map { hit ->
                val title = titles.getOrPut(hit.conversationId) {
                    runCatching { conversations.conversation(hit.conversationId)?.title }.getOrNull() ?: hit.peerOrGroupId
                }
                SearchRow(hit, title)
            }
            if (_state.value.query.trim() == query) {
                _state.update {
                    it.copy(rows = rows, searching = false, searched = true, isLocalFallback = result.isLocalFallback)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(searching = false, searched = true, rows = emptyList(), error = e.userMessage()) }
        }
    }

    companion object {
        const val DEBOUNCE_MILLIS = 300L
    }
}
