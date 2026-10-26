package com.goim.kmp.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goim.kmp.designsystem.ConfirmDialog
import com.goim.kmp.designsystem.GoImFormat
import com.goim.kmp.designsystem.GoImIcons
import com.goim.kmp.designsystem.GoImSize
import com.goim.kmp.designsystem.GoImSpacing
import com.goim.kmp.designsystem.SettingsRow
import com.goim.kmp.designsystem.SettingsSection
import com.goim.kmp.designsystem.UserAvatar
import com.goim.kmp.model.AdminUser

/**
 * 管理后台的用户详情：资料、查看会话、禁用 / 启用、删除。数据取自 [viewModel] 的用户列表，
 * 改动即时反映到列表；用户被删除后自动返回。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminUserDetailScreen(
    viewModel: AdminViewModel,
    uid: String,
    onBack: () -> Unit,
    onOpenConversations: (AdminUser) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val user = state.users.items.firstOrNull { it.uid == uid }
    val snackbar = remember { SnackbarHostState() }
    var confirmDisable by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(user == null) {
        if (user == null) onBack()
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("用户详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (user == null) return@Scaffold
        val isSelf = user.uid == viewModel.selfUid
        val updating = state.updatingUser == user.uid
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Header(user, isSelf)
            SettingsSection(title = "资料") {
                SettingsRow(title = "用户 ID", value = user.uid)
                SettingsRow(title = "邮箱", value = user.email.ifEmpty { "未绑定" })
                if (user.createdAtMs > 0) SettingsRow(title = "注册时间", value = GoImFormat.messageTime(user.createdAtMs))
                SettingsRow(title = "角色", value = if (user.isAdmin) "管理员" else "普通用户")
            }
            SettingsSection(
                title = "管理",
                footer = if (isSelf) "不能禁用或删除自己的账号。" else "禁用后该用户立即下线且无法登录，启用后恢复正常。",
            ) {
                SettingsRow(title = "查看会话", icon = GoImIcons.Chat, onClick = { onOpenConversations(user) })
                if (!isSelf) {
                    val toggle = { if (user.isDisabled) viewModel.setUserDisabled(user, false) else confirmDisable = true }
                    SettingsRow(
                        title = "禁用账号",
                        icon = GoImIcons.Block,
                        onClick = if (updating) null else toggle,
                        trailing = {
                            Switch(checked = user.isDisabled, enabled = !updating, onCheckedChange = { toggle() })
                        },
                    )
                    SettingsRow(title = "删除用户", icon = Icons.Filled.Delete, destructive = true, onClick = { confirmDelete = true })
                }
            }
        }
    }

    if (user != null && confirmDisable) {
        ConfirmDialog(
            title = "禁用用户？",
            text = "${user.displayName}（${user.uid}）会立即下线，且无法再登录，直到重新启用。",
            confirmLabel = "禁用",
            destructive = true,
            icon = GoImIcons.Block,
            onConfirm = {
                viewModel.setUserDisabled(user, true)
                confirmDisable = false
            },
            onDismiss = { confirmDisable = false },
        )
    }
    if (user != null && confirmDelete) {
        ConfirmDialog(
            title = "删除用户？",
            text = "将删除 ${user.displayName}（${user.uid}）的账号，此操作不可撤销。",
            confirmLabel = "删除",
            destructive = true,
            icon = Icons.Filled.Delete,
            onConfirm = {
                viewModel.deleteUser(user)
                confirmDelete = false
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun Header(user: AdminUser, isSelf: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(GoImSpacing.s6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GoImSpacing.s2),
    ) {
        UserAvatar(user.uid, user.displayName, size = GoImSize.avatar2xl)
        Text(user.displayName, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(GoImSpacing.s2)) {
            if (user.isAdmin) Tag("管理员", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            if (user.isDisabled) Tag("已禁用", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            if (isSelf) Tag("我", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
