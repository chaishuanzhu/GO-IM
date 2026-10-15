package com.goim.kmp.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goim.kmp.designsystem.SettingsRow
import com.goim.kmp.designsystem.SettingsSection
import com.goim.kmp.designsystem.TextInputDialog
import com.goim.kmp.designsystem.UserAvatar
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch

/** 个人资料：点头像更换，昵称 / 邮箱可修改，用户 ID 可复制。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(viewModel: ProfileViewModel, onBack: () -> Unit) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var editNickname by remember { mutableStateOf(false) }
    var bindEmail by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val picker = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        if (file != null) scope.launch { viewModel.updateAvatar(file.readBytes(), file.name) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("个人资料") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !busy, role = Role.Button, onClickLabel = "更换头像") { picker.launch() },
                    contentAlignment = Alignment.Center,
                ) {
                    UserAvatar(profile.uid, profile.displayName, size = 96.dp, avatarUrl = profile.avatarUrl)
                    if (busy) CircularProgressIndicator(Modifier.size(40.dp))
                }
                TextButton(onClick = { picker.launch() }, enabled = !busy) { Text("更换头像") }
            }
            SettingsSection(title = "资料") {
                SettingsRow(title = "昵称", value = profile.displayName, icon = Icons.Filled.Face, onClick = { editNickname = true })
                SettingsRow(
                    title = "用户 ID",
                    value = profile.uid,
                    icon = Icons.Filled.Person,
                    onClick = {
                        clipboard.setText(AnnotatedString(profile.uid))
                        viewModel.copied()
                    },
                )
                SettingsRow(
                    title = "邮箱",
                    value = profile.email.ifEmpty { "未绑定，绑定后可用邮箱登录和找回密码" },
                    icon = Icons.Filled.Email,
                    onClick = {
                        viewModel.resetBind()
                        bindEmail = true
                    },
                )
            }
        }
    }

    if (editNickname) {
        TextInputDialog(
            title = "修改昵称",
            message = null,
            initial = profile.nickname,
            confirmLabel = "保存",
            validate = {
                when {
                    it.isBlank() -> "昵称不能为空"
                    it.trim().length > 32 -> "昵称最多 32 个字"
                    else -> null
                }
            },
            onConfirm = {
                editNickname = false
                if (it.trim() != profile.nickname) viewModel.updateNickname(it)
            },
            onDismiss = { editNickname = false },
        )
    }
    if (bindEmail) {
        BindEmailDialog(viewModel, current = profile.email, onDismiss = { bindEmail = false })
    }
}

@Composable
private fun BindEmailDialog(viewModel: ProfileViewModel, current: String, onDismiss: () -> Unit) {
    val state by viewModel.bind.collectAsStateWithLifecycle()
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val emailValid = email.trim().let { it.count { c -> c == '@' } == 1 && it.substringAfter('@').contains('.') && it.indexOf('@') > 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current.isEmpty()) "绑定邮箱" else "更换邮箱") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (current.isNotEmpty()) {
                    Text("当前邮箱：$current", style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("邮箱") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("验证码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    trailingIcon = {
                        TextButton(
                            onClick = { viewModel.sendBindCode(email.trim()) },
                            enabled = emailValid && !state.sending && state.cooldown == 0,
                        ) {
                            Text(
                                when {
                                    state.sending -> "发送中…"
                                    state.cooldown > 0 -> "${state.cooldown} 秒"
                                    else -> "获取验证码"
                                },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state.codeSent && state.error == null) {
                    Text("验证码已发送，10 分钟内有效", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.bindEmail(email, code, onDone = onDismiss) },
                enabled = emailValid && code.length == 6 && !state.submitting,
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
