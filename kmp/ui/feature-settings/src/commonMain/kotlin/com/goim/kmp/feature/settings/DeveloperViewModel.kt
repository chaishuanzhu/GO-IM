package com.goim.kmp.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Severity
import com.goim.kmp.common.LogEntry
import com.goim.kmp.common.userMessage
import com.goim.kmp.model.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 开发者工具的数据来源；由 app 层按当前会话装配。 */
class DeveloperDeps(
    val logs: StateFlow<List<LogEntry>>,
    val clearLogs: () -> Unit,
    val connectionState: StateFlow<ConnectionState>,
    val reconnect: suspend () -> Unit,
    /** 账号、服务器、平台等静态信息。 */
    val info: () -> List<Pair<String, String>>,
    val databaseStats: suspend () -> List<Pair<String, Long>>,
    val storageUsage: suspend () -> List<Pair<String, Long>>,
    /** 导出数据库快照，返回临时文件路径。 */
    val exportDatabase: suspend () -> String,
    val discardExport: (path: String) -> Unit,
    val clearImageCache: () -> Unit,
)

enum class LogLevelFilter(val title: String, val min: Severity) {
    ALL("全部", Severity.Verbose),
    INFO("Info+", Severity.Info),
    WARN("Warn+", Severity.Warn),
}

enum class NetworkFilter(val title: String, val tags: Set<String>) {
    ALL("全部", setOf("HTTP", "IM")),
    HTTP("HTTP", setOf("HTTP")),
    IM("长连接", setOf("IM")),
}

data class DeveloperUiState(
    val levelFilter: LogLevelFilter = LogLevelFilter.ALL,
    val networkFilter: NetworkFilter = NetworkFilter.ALL,
    val query: String = "",
    val tableCounts: List<Pair<String, Long>> = emptyList(),
    val storage: List<Pair<String, Long>> = emptyList(),
    val busy: Boolean = false,
    val notice: String? = null,
)

class DeveloperViewModel(private val deps: DeveloperDeps) : ViewModel() {
    private val _state = MutableStateFlow(DeveloperUiState())
    val state: StateFlow<DeveloperUiState> = _state.asStateFlow()

    val connectionState: StateFlow<ConnectionState> get() = deps.connectionState

    val info: List<Pair<String, String>> by lazy { deps.info() }

    /** 「日志」页：按级别与关键字过滤，最新在前。 */
    val logs: StateFlow<List<LogEntry>> = combine(deps.logs, _state) { entries, s ->
        filter(entries, s.query) { it.severity >= s.levelFilter.min }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 「网络」页：HTTP 请求摘要与长连接收发帧。 */
    val networkLogs: StateFlow<List<LogEntry>> = combine(deps.logs, _state) { entries, s ->
        filter(entries, s.query) { it.tag in s.networkFilter.tags }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refreshData()
    }

    fun setLevelFilter(filter: LogLevelFilter) = _state.update { it.copy(levelFilter = filter) }

    fun setNetworkFilter(filter: NetworkFilter) = _state.update { it.copy(networkFilter = filter) }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun clearLogs() = deps.clearLogs()

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun refreshData() {
        viewModelScope.launch {
            val counts = runCatching { deps.databaseStats() }.getOrDefault(emptyList())
            val storage = runCatching { deps.storageUsage() }.getOrDefault(emptyList())
            _state.update { it.copy(tableCounts = counts, storage = storage) }
        }
    }

    fun reconnect() {
        viewModelScope.launch { runCatching { deps.reconnect() } }
    }

    fun clearImageCache() {
        deps.clearImageCache()
        _state.update { it.copy(notice = "图片内存与磁盘缓存已清除") }
    }

    /** 生成快照后交给 [save]（文件保存对话框）；无论成败都删除临时文件。 */
    fun exportDatabase(save: suspend (path: String) -> Boolean) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            var path: String? = null
            try {
                path = deps.exportDatabase()
                val saved = save(path)
                _state.update { it.copy(notice = if (saved) "数据库已导出" else null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = "导出失败：${e.userMessage()}") }
            } finally {
                path?.let(deps.discardExport)
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private inline fun filter(entries: List<LogEntry>, query: String, predicate: (LogEntry) -> Boolean): List<LogEntry> {
        val q = query.trim()
        return entries.asReversed().filter { predicate(it) && (q.isEmpty() || q in it.message || q in it.tag) }
    }
}
