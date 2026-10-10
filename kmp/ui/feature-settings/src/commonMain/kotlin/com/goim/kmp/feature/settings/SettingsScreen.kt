package com.goim.kmp.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.goim.kmp.designsystem.ChoiceDialog
import com.goim.kmp.designsystem.ConfirmDialog
import com.goim.kmp.designsystem.TonalIcon
import com.goim.kmp.designsystem.GoImIcons
import com.goim.kmp.designsystem.MonogramAvatar
import com.goim.kmp.designsystem.SettingsRow
import com.goim.kmp.designsystem.SettingsSection
import com.goim.kmp.designsystem.TextInputDialog
import com.goim.kmp.designsystem.displayName
import com.goim.kmp.designsystem.isValidApiBaseUrl
import com.goim.kmp.designsystem.title
import com.goim.kmp.model.Appearance
import com.goim.kmp.model.ConnectionState
import com.goim.kmp.model.TransportKind
import com.goim.kmp.model.User

/** 主页「设置」Tab。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onOpenDeveloper: (() -> Unit)? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    var showAccounts by remember { mutableStateOf(false) }
    var pickAppearance by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text("设置") }, scrollBehavior = scrollBehavior) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            state.user?.let { user ->
                ProfileCard(user)
                SettingsSection(title = "账号") {
                    if (viewModel.supportsAccounts) {
                        SettingsRow(
                            title = "切换账号",
                            value = if (state.accounts.size > 1) "${state.accounts.size} 个账号" else null,
                            icon = Icons.Filled.AccountCircle,
                            onClick = { showAccounts = true },
                        )
                    }
                    SettingsRow(
                        title = "退出登录",
                        icon = GoImIcons.Logout,
                        destructive = true,
                        onClick = { confirmLogout = true },
                    )
                }
            }
            SettingsSection(title = "通用") {
                SettingsRow(
                    title = "外观",
                    value = state.appearance.title,
                    icon = GoImIcons.DarkMode,
                    onClick = { pickAppearance = true },
                )
            }
            ConnectionSection(
                state = state,
                footer = "API 地址用于登录与文件上传；传输协议用于实时消息。",
                onApiBaseUrl = viewModel::setApiBaseUrl,
                onTransport = viewModel::setTransport,
            )
            if (onOpenDeveloper != null) {
                SettingsSection(title = "开发者") {
                    SettingsRow(title = "开发者工具", value = "日志 · 网络 · 数据库", icon = Icons.Filled.Build, onClick = onOpenDeveloper)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmLogout) {
        ConfirmDialog(
            title = "退出登录？",
            text = "将删除本机保存的该账号登录信息；聊天记录保留。",
            confirmLabel = "退出登录",
            destructive = true,
            icon = GoImIcons.Logout,
            onConfirm = {
                confirmLogout = false
                viewModel.logout()
            },
            onDismiss = { confirmLogout = false },
        )
    }
    if (showAccounts) {
        AccountSwitcherDialog(
            current = state.user?.uid,
            accounts = state.accounts,
            onSwitch = {
                showAccounts = false
                viewModel.switchAccount(it)
            },
            onForget = viewModel::forgetAccount,
            onAdd = {
                showAccounts = false
                viewModel.addAccount()
            },
            onDismiss = { showAccounts = false },
        )
    }
    if (pickAppearance) {
        ChoiceDialog(
            title = "外观",
            options = Appearance.entries,
            selected = state.appearance,
            label = { it.title },
            onSelect = {
                pickAppearance = false
                viewModel.setAppearance(it)
            },
            onDismiss = { pickAppearance = false },
        )
    }
}

@Composable
private fun ProfileCard(user: User) {
    val name = user.username.ifEmpty { user.uid }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            MonogramAvatar(name, size = 64.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("ID：${user.uid}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AccountSwitcherDialog(
    current: String?,
    accounts: List<User>,
    onSwitch: (String) -> Unit,
    onForget: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    var forgetting by remember { mutableStateOf<User?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换账号") },
        text = {
            Column {
                accounts.forEach { account ->
                    val isCurrent = account.uid == current
                    val name = account.username.ifEmpty { account.uid }
                    ListItem(
                        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(if (isCurrent) "当前账号" else account.uid) },
                        leadingContent = { MonogramAvatar(name, size = 40.dp) },
                        trailingContent = {
                            if (isCurrent) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            } else {
                                IconButton(onClick = { forgetting = account }) {
                                    Icon(Icons.Filled.Close, contentDescription = "移除 $name")
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        ),
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.large)
                            .clickable(enabled = !isCurrent, role = Role.Button) { onSwitch(account.uid) }
                            .semantics {
                                selected = isCurrent
                                contentDescription = if (isCurrent) "$name，当前账号" else "切换到 $name"
                            },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ListItem(
                    headlineContent = { Text("添加账号", color = MaterialTheme.colorScheme.primary) },
                    leadingContent = { TonalIcon(Icons.Filled.Add) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clip(MaterialTheme.shapes.large).clickable(role = Role.Button, onClick = onAdd),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
    forgetting?.let { account ->
        ConfirmDialog(
            title = "移除账号？",
            text = "移除 ${account.username.ifEmpty { account.uid }} 的登录信息，下次需重新登录；聊天记录保留。",
            confirmLabel = "移除",
            destructive = true,
            onConfirm = {
                onForget(account.uid)
                forgetting = null
            },
            onDismiss = { forgetting = null },
        )
    }
}

/** 登录页右上角「服务器设置」。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("服务器设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ConnectionSection(
                state = state,
                footer = "API 地址用于登录与文件上传；传输方式用于实时消息（登录后生效）。",
                onApiBaseUrl = viewModel::setApiBaseUrl,
                onTransport = viewModel::setTransport,
            )
        }
    }
}

@Composable
private fun ConnectionSection(
    state: SettingsUiState,
    footer: String,
    onApiBaseUrl: (String) -> Unit,
    onTransport: (TransportKind) -> Unit,
) {
    var editUrl by remember { mutableStateOf(false) }
    var pickTransport by remember { mutableStateOf(false) }

    SettingsSection(title = "连接", footer = footer) {
        state.connectionState?.let { conn ->
            SettingsRow(
                title = "连接状态",
                value = conn.title,
                icon = when (conn) {
                    ConnectionState.CONNECTED -> GoImIcons.Antenna
                    ConnectionState.AUTH_EXPIRED, ConnectionState.DISCONNECTED -> Icons.Filled.Warning
                    else -> Icons.Filled.Refresh
                },
            )
        }
        SettingsRow(title = "API 地址", value = state.apiBaseUrl, icon = GoImIcons.Link, onClick = { editUrl = true })
        SettingsRow(
            title = "传输方式",
            value = if (state.isSwitching) "切换中…" else state.transport.displayName,
            icon = GoImIcons.Antenna,
            onClick = { pickTransport = true },
        )
    }

    if (editUrl) {
        TextInputDialog(
            title = "API 地址",
            message = "例如 https://im.chaisz.com",
            initial = state.apiBaseUrl,
            confirmLabel = "保存",
            keyboardType = KeyboardType.Uri,
            validate = { if (isValidApiBaseUrl(it)) null else "请输入完整 URL，例如 https://im.chaisz.com" },
            onConfirm = {
                onApiBaseUrl(it)
                editUrl = false
            },
            onDismiss = { editUrl = false },
        )
    }
    if (pickTransport) {
        ChoiceDialog(
            title = "传输方式",
            options = TransportKind.entries,
            selected = state.transport,
            label = { it.displayName },
            onSelect = {
                pickTransport = false
                onTransport(it)
            },
            onDismiss = { pickTransport = false },
        )
    }
}
