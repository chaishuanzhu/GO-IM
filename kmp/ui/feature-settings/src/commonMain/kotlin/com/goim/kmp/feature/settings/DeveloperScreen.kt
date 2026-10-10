package com.goim.kmp.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Severity
import com.goim.kmp.common.LogEntry
import com.goim.kmp.designsystem.EmptyState
import com.goim.kmp.designsystem.GoImFormat
import com.goim.kmp.designsystem.GoImIcons
import com.goim.kmp.designsystem.SettingsRow
import com.goim.kmp.designsystem.SettingsSection
import com.goim.kmp.designsystem.title
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.write
import kotlinx.coroutines.CompletableDeferred

private enum class DevTab(val title: String) { LOGS("日志"), NETWORK("网络"), DATA("数据") }

/** 开发者工具（对标 iOS FLEX）：日志、网络日志、数据库与缓存。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperScreen(viewModel: DeveloperViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(DevTab.LOGS) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("开发者工具") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    if (tab != DevTab.DATA) {
                        IconButton(onClick = viewModel::clearLogs) { Icon(Icons.Filled.Delete, contentDescription = "清空日志") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                DevTab.entries.forEach { t ->
                    Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.title) })
                }
            }
            when (tab) {
                DevTab.LOGS -> {
                    val logs by viewModel.logs.collectAsStateWithLifecycle()
                    LogPane(
                        logs = logs,
                        query = state.query,
                        onQuery = viewModel::setQuery,
                        chips = {
                            LogLevelFilter.entries.forEach { f ->
                                FilterChip(selected = state.levelFilter == f, onClick = { viewModel.setLevelFilter(f) }, label = { Text(f.title) })
                            }
                        },
                    )
                }
                DevTab.NETWORK -> {
                    val logs by viewModel.networkLogs.collectAsStateWithLifecycle()
                    LogPane(
                        logs = logs,
                        query = state.query,
                        onQuery = viewModel::setQuery,
                        chips = {
                            NetworkFilter.entries.forEach { f ->
                                FilterChip(selected = state.networkFilter == f, onClick = { viewModel.setNetworkFilter(f) }, label = { Text(f.title) })
                            }
                        },
                    )
                }
                DevTab.DATA -> DataPane(viewModel, state)
            }
        }
    }
}

@Composable
private fun LogPane(
    logs: List<LogEntry>,
    query: String,
    onQuery: (String) -> Unit,
    chips: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("过滤关键字") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
        if (logs.isEmpty()) {
            EmptyState(GoImIcons.Storage, "暂无日志")
            return
        }
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize()) {
                items(logs, key = { it.id }, contentType = { "log" }) { entry ->
                    LogRow(entry)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(
            "${GoImFormat.logTime(entry.timestampMs)}  ${entry.severity.name.first()}  ${entry.tag}",
            style = MaterialTheme.typography.labelSmall,
            color = severityColor(entry.severity),
        )
        Text(entry.message, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun severityColor(severity: Severity): Color = when {
    severity >= Severity.Error -> MaterialTheme.colorScheme.error
    severity == Severity.Warn -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun DataPane(viewModel: DeveloperViewModel, state: DeveloperUiState) {
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<CompletableDeferred<PlatformFile?>?>(null) }
    val saver = rememberFileSaverLauncher { file -> pending?.complete(file) }
    val currentSaver by rememberUpdatedState(saver)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSection(title = "环境") {
            viewModel.info.forEach { (k, v) -> SettingsRow(title = k, value = v) }
            SettingsRow(title = "连接状态", value = connection.title, icon = GoImIcons.Antenna)
            SettingsRow(title = "立即重连", icon = Icons.Filled.Refresh, onClick = viewModel::reconnect)
        }
        SettingsSection(title = "数据库") {
            state.tableCounts.forEach { (table, count) -> SettingsRow(title = table, value = "$count 行") }
            SettingsRow(
                title = if (state.busy) "导出中…" else "导出数据库",
                icon = GoImIcons.Upload,
                onClick = {
                    viewModel.exportDatabase { path ->
                        val deferred = CompletableDeferred<PlatformFile?>()
                        pending = deferred
                        currentSaver.launch(suggestedName = path.substringAfterLast('/').substringBeforeLast('.'), extension = "sqlite")
                        val target = deferred.await() ?: return@exportDatabase false
                        target.write(PlatformFile(path))
                        true
                    }
                },
            )
        }
        SettingsSection(title = "存储", footer = "图片缓存为 Coil 内存 / 磁盘缓存；media / files / tmp 为当前账号目录。") {
            state.storage.forEach { (dir, bytes) -> SettingsRow(title = dir, value = GoImFormat.fileSize(bytes), icon = GoImIcons.Storage) }
            SettingsRow(title = "清除图片缓存", onClick = viewModel::clearImageCache)
            SettingsRow(title = "刷新统计", icon = Icons.Filled.Refresh, onClick = viewModel::refreshData)
        }
    }
}
