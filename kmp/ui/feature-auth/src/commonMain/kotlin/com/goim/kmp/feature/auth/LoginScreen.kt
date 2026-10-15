package com.goim.kmp.feature.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goim.kmp.designsystem.GoImIcons
import com.goim.kmp.designsystem.UserAvatar
import com.goim.kmp.designsystem.TonalIcon
import com.goim.kmp.designsystem.GoImSpacing
import com.goim.kmp.designsystem.GoImSize
import com.goim.kmp.designsystem.GoImDimens
import com.goim.kmp.model.User
import com.goim.kmp.designsystem.GoImTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onOpenServerSettings: () -> Unit,
    /** 本机已保存凭据的账号（添加账号 / 退出后），点按直接进入。 */
    savedAccounts: List<User> = emptyList(),
    onUseSavedAccount: (uid: String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPassword by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    IconButton(onClick = onOpenServerSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "服务器设置")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = GoImDimens.formMaxWidth).fillMaxWidth().padding(horizontal = GoImSpacing.s6, vertical = GoImSpacing.s4),
                verticalArrangement = Arrangement.spacedBy(GoImSpacing.s4),
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(GoImSpacing.s2),
                ) {
                    TonalIcon(
                        GoImIcons.Chat,
                        size = GoImSize.avatar2xl,
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text("GO-IM", style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
                    Text(
                        when (state.mode) {
                            AuthMode.LOGIN -> "登录以继续"
                            AuthMode.REGISTER -> "用邮箱创建新账号"
                            AuthMode.RESET -> "通过邮箱验证码重置密码"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state.mode != AuthMode.RESET) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(AuthMode.LOGIN to "登录", AuthMode.REGISTER to "注册").forEachIndexed { index, (mode, label) ->
                            SegmentedButton(
                                selected = state.mode == mode,
                                onClick = { viewModel.onModeChange(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                            ) { Text(label) }
                        }
                    }
                }

                if (state.mode == AuthMode.LOGIN) {
                    GoImTextField(
                        value = state.account,
                        onValueChange = viewModel::onAccountChange,
                        placeholder = "邮箱或用户 ID",
                        leadingIcon = Icons.Filled.Person,
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    GoImTextField(
                        value = state.email,
                        onValueChange = viewModel::onEmailChange,
                        placeholder = "邮箱",
                        leadingIcon = Icons.Filled.Email,
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GoImTextField(
                        value = state.code,
                        onValueChange = viewModel::onCodeChange,
                        placeholder = "验证码",
                        leadingIcon = Icons.Filled.CheckCircle,
                        trailing = {
                            TextButton(onClick = viewModel::sendCode, enabled = state.canSendCode) {
                                Text(
                                    when {
                                        state.isSendingCode -> "发送中…"
                                        state.codeCooldown > 0 -> "${state.codeCooldown} 秒后重发"
                                        else -> "获取验证码"
                                    },
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                GoImTextField(
                    value = state.password,
                    onValueChange = viewModel::onPasswordChange,
                    placeholder = if (state.mode == AuthMode.RESET) "新密码" else "密码",
                    supportingText = if (state.mode == AuthMode.LOGIN) null else "至少 6 位",
                    leadingIcon = Icons.Filled.Lock,
                    trailing = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) GoImIcons.VisibilityOff else GoImIcons.Visibility,
                                contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { viewModel.submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )

                state.error?.let { ErrorBanner(it) }
                state.info?.let { InfoBanner(it) }

                Button(
                    onClick = viewModel::submit,
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            Modifier.size(GoImSize.iconSm),
                            strokeWidth = GoImSize.strokeThin,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(
                            when (state.mode) {
                                AuthMode.LOGIN -> "登录"
                                AuthMode.REGISTER -> "注册并登录"
                                AuthMode.RESET -> "重置密码"
                            },
                        )
                    }
                }
                when (state.mode) {
                    AuthMode.LOGIN -> TextButton(
                        onClick = { viewModel.onModeChange(AuthMode.RESET) },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("忘记密码？") }
                    AuthMode.RESET -> TextButton(
                        onClick = { viewModel.onModeChange(AuthMode.LOGIN) },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text("返回登录") }
                    AuthMode.REGISTER -> Unit
                }

                if (savedAccounts.isNotEmpty()) {
                    Spacer(Modifier.height(GoImSpacing.s2))
                    Text(
                        "本机已登录的账号",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { heading() },
                    )
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        savedAccounts.forEach { account ->
                            val name = account.displayName
                            ListItem(
                                headlineContent = { Text(name) },
                                supportingContent = { Text(account.email.ifEmpty { "ID：${account.uid}" }) },
                                leadingContent = { UserAvatar(account.uid, name, size = GoImSize.avatarMd, avatarUrl = account.avatarUrl) },
                                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable(enabled = !state.isLoading, role = Role.Button, onClickLabel = "进入") {
                                    onUseSavedAccount(account.uid)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = GoImSpacing.s4, vertical = GoImSpacing.s3), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(GoImSize.iconSm))
            Spacer(Modifier.width(GoImSpacing.s3))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun InfoBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = GoImSpacing.s4, vertical = GoImSpacing.s3), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(GoImSize.iconSm))
            Spacer(Modifier.width(GoImSpacing.s3))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
