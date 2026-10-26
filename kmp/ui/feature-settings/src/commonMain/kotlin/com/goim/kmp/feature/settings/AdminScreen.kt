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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goim.kmp.designsystem.EmptyState
import com.goim.kmp.designsystem.GoImFormat
import com.goim.kmp.designsystem.GoImIcons
import com.goim.kmp.designsystem.GoImSize
import com.goim.kmp.designsystem.GoImSpacing
import com.goim.kmp.designsystem.SettingsRow
import com.goim.kmp.designsystem.SettingsSection
import com.goim.kmp.designsystem.TextInputDialog
import com.goim.kmp.designsystem.UserAvatar
import com.goim.kmp.model.AdminCreatedUser
import com.goim.kmp.model.AdminMessage
import com.goim.kmp.model.AdminStats
import com.goim.kmp.model.AdminUser
import com.goim.kmp.model.ChatType

private enum class AdminTab(val title: String) { OVERVIEW("概览"), USERS("用户"), MESSAGES("消息") }

/** 管理后台：系统概览、用户管理（添加用户；点用户进入详情）、全站消息浏览（点消息跳到所在会话；仅管理员可进入，权限由网关校验）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    viewModel: AdminViewModel,
    onBack: () -> Unit,
    onOpenUser: (AdminUser) -> Unit,
    onOpenMessage: (AdminMessage) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(AdminTab.OVERVIEW) }
    var addingUser by remember { mutableStateOf(false) }
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
                    if (tab == AdminTab.USERS) {
                        IconButton(onClick = { addingUser = true }, enabled = !state.creatingUser) {
                            Icon(GoImIcons.PersonAdd, contentDescription = "添加用户")
                        }
                    }
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
                    UserRow(user, isSelf = user.uid == viewModel.selfUid, onOpen = { onOpenUser(user) })
                }
                AdminTab.MESSAGES -> PagedList(
                    page = state.messages,
                    emptyTitle = "暂无消息",
                    key = { it.msgId },
                    onLoadMore = viewModel::loadMoreMessages,
                ) { message ->
                    MessageRow(message, onOpen = { onOpenMessage(message) })
                }
            }
        }
    }

    if (addingUser) {
        TextInputDialog(
            title = "添加用户",
            message = "将随机生成初始密码并发送到该邮箱，用户首次登录须修改密码。",
            placeholder = "邮箱",
            confirmLabel = "添加",
            keyboardType = KeyboardType.Email,
            validate = { if (EMAIL_PATTERN.matches(it)) null else "邮箱格式不正确" },
            onConfirm = {
                viewModel.createUser(it)
                addingUser = false
            },
            onDismiss = { addingUser = false },
        )
    }
    state.unsentPassword?.let { UnsentPasswordDialog(it, onDismiss = viewModel::dismissUnsentPassword) }
}

private val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

/** 账号已创建但初始密码邮件发送失败：只展示这一次，由管理员转交。 */
@Composable
private fun UnsentPasswordDialog(created: AdminCreatedUser, onDismiss: () -> Unit) {
    val password = created.initialPassword.orEmpty()
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
        title = { Text("邮件未发出") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(GoImSpacing.s3)) {
                Text("已添加 ${created.user.email}（ID ${created.user.uid}），但初始密码邮件发送失败。请把下面的密码转交给该用户，关闭后无法再次查看。")
                SelectionContainer {
                    Text(password, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(password)) }) { Text("复制密码") } },
    )
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
private fun UserRow(user: AdminUser, isSelf: Boolean, onOpen: () -> Unit) {
    val meta = buildList {
        add("ID ${user.uid}")
        if (user.email.isNotEmpty()) add(user.email)
        if (user.createdAtMs > 0) add("注册于 ${GoImFormat.messageTime(user.createdAtMs)}")
    }.joinToString(" · ")
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = "查看详情", onClick = onOpen),
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
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

@Composable
private fun MessageRow(message: AdminMessage, onOpen: () -> Unit) {
    val target = if (message.chatType == ChatType.GROUP) "群 ${message.toUid}" else message.toUid
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = "在会话中查看", onClick = onOpen),
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
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
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
