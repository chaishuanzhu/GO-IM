package com.goim.kmp.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goim.kmp.designsystem.ConfirmDialog
import com.goim.kmp.designsystem.EmptyState
import com.goim.kmp.designsystem.GoImFormat
import com.goim.kmp.designsystem.GoImIcons
import com.goim.kmp.designsystem.GoImSize
import com.goim.kmp.designsystem.GoImSpacing
import com.goim.kmp.designsystem.SettingsRow
import com.goim.kmp.designsystem.SettingsSection
import com.goim.kmp.designsystem.UserAvatar
import com.goim.kmp.model.AdminMessage
import com.goim.kmp.model.AdminStats
import com.goim.kmp.model.AdminUser
import com.goim.kmp.model.ChatType

private enum class AdminTab(val title: String) { OVERVIEW("概览"), USERS("用户"), MESSAGES("消息") }

/** 管理后台：系统概览、用户管理（点用户查看其会话）、全站消息浏览（仅管理员可进入，权限由网关校验）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(viewModel: AdminViewModel, onBack: () -> Unit, onOpenUser: (AdminUser) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(AdminTab.OVERVIEW) }
    var deletingUser by remember { mutableStateOf<AdminUser?>(null) }
    var deletingMessage by remember { mutableStateOf<AdminMessage?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }
    LaunchedEffect(tab) {
        when (tab) {
            AdminTab.OVERVIEW -> Unit
            AdminTab.USERS -> viewModel.ensureUsers()
            AdminTab.MESSAGES -> viewModel.ensureMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("管理后台") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(onClick = {
                        when (tab) {
                            AdminTab.OVERVIEW -> viewModel.refreshStats()
                            AdminTab.USERS -> viewModel.refreshUsers()
                            AdminTab.MESSAGES -> viewModel.refreshMessages()
                        }
                    }) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                AdminTab.entries.forEach { t ->
                    Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.title) })
                }
            }
            when (tab) {
                AdminTab.OVERVIEW -> OverviewPane(state)
                AdminTab.USERS -> PagedList(
                    page = state.users,
                    emptyTitle = "暂无用户",
                    key = { it.uid },
                    onLoadMore = viewModel::loadMoreUsers,
                    header = if (state.usersTotal > 0) "共 ${state.usersTotal} 个用户" else null,
                ) { user ->
                    UserRow(
                        user,
                        isSelf = user.uid == viewModel.selfUid,
                        onOpen = { onOpenUser(user) },
                        onDelete = { deletingUser = user },
                    )
                }
                AdminTab.MESSAGES -> PagedList(
                    page = state.messages,
                    emptyTitle = "暂无消息",
                    key = { it.msgId },
                    onLoadMore = viewModel::loadMoreMessages,
                ) { message ->
                    MessageRow(message, onDelete = { deletingMessage = message })
                }
            }
        }
    }

    deletingUser?.let { user ->
        ConfirmDialog(
            title = "删除用户？",
            text = "将删除 ${user.displayName}（${user.uid}）的账号，此操作不可撤销。",
            confirmLabel = "删除",
            destructive = true,
            icon = Icons.Filled.Delete,
            onConfirm = {
                viewModel.deleteUser(user)
                deletingUser = null
            },
            onDismiss = { deletingUser = null },
        )
    }
    deletingMessage?.let { message ->
        ConfirmDialog(
            title = "删除消息？",
            text = "将从服务器删除这条消息，已收到的客户端本地记录不受影响。",
            confirmLabel = "删除",
            destructive = true,
            icon = Icons.Filled.Delete,
            onConfirm = {
                viewModel.deleteMessage(message)
                deletingMessage = null
            },
            onDismiss = { deletingMessage = null },
        )
    }
}

@Composable
private fun OverviewPane(state: AdminUiState) {
    val stats = state.stats
    when {
        stats != null -> StatsContent(stats)
        state.statsError != null -> ErrorState(state.statsError)
        else -> LoadingState()
    }
}

@Composable
private fun StatsContent(stats: AdminStats) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSection(title = "系统") {
            SettingsRow(
                title = "状态",
                value = if (stats.healthy) "正常" else "部分依赖异常",
                icon = if (stats.healthy) GoImIcons.Shield else Icons.Filled.Warning,
                destructive = !stats.healthy,
            )
            SettingsRow(title = "在线用户", value = stats.onlineUsers.toString(), icon = GoImIcons.Antenna)
            SettingsRow(title = "注册用户", value = stats.totalUsers.toString(), icon = Icons.Filled.Person)
            SettingsRow(title = "消息总数", value = stats.totalMessages.toString(), icon = GoImIcons.Chat)
        }
        SettingsSection(title = "依赖") {
            stats.dependencies.forEach { (name, status) ->
                val ok = status == "ok"
                SettingsRow(title = name, value = if (ok) "正常" else status, destructive = !ok)
            }
        }
        SettingsSection(title = "网关进程", footer = "当前处理请求的网关实例；多实例部署时各实例数值不同。") {
            SettingsRow(title = "内存", value = "${stats.allocMb} MB", icon = GoImIcons.Storage)
            SettingsRow(title = "协程", value = stats.goroutines.toString())
        }
    }
}

@Composable
private fun <T> PagedList(
    page: AdminPage<T>,
    emptyTitle: String,
    key: (T) -> Any,
    onLoadMore: () -> Unit,
    header: String? = null,
    row: @Composable (T) -> Unit,
) {
    when {
        page.items.isEmpty() && page.error != null -> ErrorState(page.error)
        page.items.isEmpty() && (page.loading || !page.loaded) -> LoadingState()
        page.items.isEmpty() -> EmptyState(GoImIcons.Storage, emptyTitle)
        else -> LazyColumn(Modifier.fillMaxSize()) {
            if (header != null) {
                item(key = "header", contentType = "header") {
                    Text(
                        header,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = GoImSpacing.s4, vertical = GoImSpacing.s2),
                    )
                }
            }
            itemsIndexed(page.items, key = { _, item -> key(item) }, contentType = { _, _ -> "row" }) { index, item ->
                row(item)
                HorizontalDivider()
                if (index == page.items.lastIndex && !page.endReached) {
                    LaunchedEffect(page.items.size) { onLoadMore() }
                }
            }
            if (page.loading) {
                item(key = "loading", contentType = "loading") {
                    Box(Modifier.fillMaxWidth().padding(GoImSpacing.s4), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.padding(GoImSpacing.s1))
                    }
                }
            }
        }
    }
}

@Composable
private fun UserRow(user: AdminUser, isSelf: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val meta = buildList {
        add("ID ${user.uid}")
        if (user.email.isNotEmpty()) add(user.email)
        if (user.createdAtMs > 0) add("注册于 ${GoImFormat.messageTime(user.createdAtMs)}")
    }.joinToString(" · ")
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = "查看会话", onClick = onOpen),
        leadingContent = { UserAvatar(user.uid, user.displayName, size = GoImSize.avatarMd, avatarUrl = "") },
        headlineContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(GoImSpacing.s2), verticalAlignment = Alignment.CenterVertically) {
                Text(user.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (user.isAdmin) Tag("管理员", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                if (user.isDisabled) Tag("已禁用", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                if (isSelf) Tag("我", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            }
        },
        supportingContent = { Text(meta, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = if (isSelf) null else {
            {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除用户 ${user.displayName}", tint = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

@Composable
private fun MessageRow(message: AdminMessage, onDelete: () -> Unit) {
    val target = if (message.chatType == ChatType.GROUP) "群 ${message.toUid}" else message.toUid
    ListItem(
        overlineContent = { Text("${message.fromUid} → $target · ${GoImFormat.messageTime(message.timestampMs)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        headlineContent = {
            Text(
                message.preview,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = if (message.recalled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = { Text("ID ${message.msgId}", style = MaterialTheme.typography.labelSmall) },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除消息", tint = MaterialTheme.colorScheme.error)
            }
        },
    )
}

@Composable
internal fun Tag(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = GoImSpacing.s2))
    }
}

@Composable
internal fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
internal fun ErrorState(message: String) {
    EmptyState(Icons.Filled.Warning, "加载失败", "$message\n点右上角刷新重试")
}
