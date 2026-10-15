package com.goim.kmp.feature.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import com.goim.kmp.designsystem.EmptyState
import com.goim.kmp.designsystem.GoImIcons
import com.goim.kmp.designsystem.TonalIcon
import com.goim.kmp.designsystem.MonogramAvatar
import com.goim.kmp.designsystem.SettingsRow
import com.goim.kmp.designsystem.SettingsSection
import com.goim.kmp.designsystem.TextInputDialog
import com.goim.kmp.designsystem.UnreadBadge
import com.goim.kmp.designsystem.UserAvatar
import com.goim.kmp.designsystem.rememberDisplayName
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import com.goim.kmp.model.Friend
import com.goim.kmp.model.UserProfile
import com.goim.kmp.model.Group

@Composable
private fun MessageSnackbar(vm: ActionViewModel, host: SnackbarHostState) {
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            vm.consumeMessage()
            host.showSnackbar(it)
        }
    }
}

@Composable
private fun PersonRow(
    title: String,
    subtitle: String? = null,
    uid: String = title,
    avatarUrl: String = "",
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { UserAvatar(uid, title, size = 40.dp, avatarUrl = avatarUrl.ifEmpty { null }) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

// region 通讯录

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    viewModel: ContactsViewModel,
    onOpenNewFriends: () -> Unit,
    onOpenGroups: () -> Unit,
    onOpenFriend: (Friend) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageSnackbar(viewModel, snackbar)

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("通讯录") },
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                ShortcutRow("新的朋友", GoImIcons.PersonAdd, badge = state.pendingCount, onClick = onOpenNewFriends)
                ShortcutRow("群组", GoImIcons.Group, badge = 0, onClick = onOpenGroups)
            }
            if (state.loaded && state.sections.isEmpty()) {
                item {
                    EmptyState(
                        GoImIcons.Contacts,
                        "暂无好友",
                        "在「新的朋友」中按昵称、邮箱或用户 ID 搜索添加",
                        Modifier.padding(top = 24.dp),
                    )
                }
            }
            state.sections.forEach { section ->
                item(key = "header-${section.letter}") {
                    Text(
                        section.letter,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(section.friends, key = { "friend-${it.uid}" }) { friend ->
                    PersonRow(
                        friend.displayName,
                        subtitle = "ID：${friend.uid}".takeIf { friend.uid != friend.displayName },
                        uid = friend.uid,
                        avatarUrl = friend.avatarUrl,
                        onClick = { onOpenFriend(friend) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortcutRow(title: String, icon: ImageVector, badge: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = {
            TonalIcon(icon, container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
        },
        trailingContent = if (badge > 0) ({ UnreadBadge(badge) }) else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

// endregion

// region 新的朋友

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewFriendsScreen(viewModel: NewFriendsViewModel, onAddFriend: () -> Unit, onBack: () -> Unit) {
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageSnackbar(viewModel, snackbar)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("新的朋友") },
                navigationIcon = { BackButton(onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                SettingsSection(title = null) {
                    SettingsRow(
                        "添加好友",
                        value = "按昵称、邮箱或用户 ID 搜索",
                        icon = Icons.Filled.Add,
                        onClick = onAddFriend,
                    )
                }
            }
            item {
                Text(
                    "好友请求",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            if (requests.isEmpty()) {
                item { EmptyState(GoImIcons.PersonAdd, "暂无新的好友请求", modifier = Modifier.padding(top = 16.dp)) }
            }
            items(requests, key = { it.fromUid }) { request ->
                PersonRow(
                    request.displayName,
                    subtitle = "ID：${request.fromUid} · 请求添加你为好友",
                    uid = request.fromUid,
                    avatarUrl = request.avatarUrl,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { viewModel.respond(request, accept = false) }, enabled = !busy) { Text("拒绝") }
                        FilledTonalButton(onClick = { viewModel.respond(request, accept = true) }, enabled = !busy) { Text("接受") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFriendScreen(viewModel: AddFriendViewModel, selfUid: String, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val friendUids by viewModel.friendUids.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val focus = remember { FocusRequester() }
    MessageSnackbar(viewModel, snackbar)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("添加好友") },
                navigationIcon = { BackButton(onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text("昵称 / 邮箱 / 用户 ID") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = viewModel::search, enabled = state.query.isNotBlank() && !state.searching) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
            )
            if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                state.error != null -> EmptyState(GoImIcons.PersonAdd, state.error.orEmpty(), modifier = Modifier.padding(top = 32.dp))
                state.searched && !state.searching && state.results.isEmpty() ->
                    EmptyState(GoImIcons.PersonAdd, "没有找到用户", modifier = Modifier.padding(top = 32.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.results, key = { it.uid }) { user ->
                    val label = when {
                        user.uid == selfUid -> "自己"
                        user.uid in friendUids -> "已是好友"
                        user.uid in state.sent -> "已发送"
                        else -> null
                    }
                    PersonRow(
                        user.displayName,
                        subtitle = "ID：${user.uid}",
                        uid = user.uid,
                        avatarUrl = user.avatarUrl,
                    ) {
                        if (label != null) {
                            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                        } else {
                            val sending = user.uid in state.sending
                            FilledTonalButton(onClick = { viewModel.sendRequest(user.uid) }, enabled = !sending) {
                                Text(if (sending) "发送中" else "添加")
                            }
                        }
                    }
                }
            }
        }
    }
}

// endregion

// region 群组

private enum class CreateStep { NAME, MEMBERS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(viewModel: GroupsViewModel, onBack: () -> Unit, onOpenGroup: (Group) -> Unit) {
    val groups by viewModel.groupList.collectAsStateWithLifecycle()
    val friends by viewModel.friendList.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var createStep by remember { mutableStateOf<CreateStep?>(null) }
    var pendingName by remember { mutableStateOf("") }
    var showJoin by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf<Group?>(null) }
    MessageSnackbar(viewModel, snackbar)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("群组") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.Add, contentDescription = "创建或加入群聊") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("创建群聊") }, onClick = { menuOpen = false; createStep = CreateStep.NAME })
                            DropdownMenuItem(text = { Text("加入群聊") }, onClick = { menuOpen = false; showJoin = true })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (groups.isEmpty()) {
                EmptyState(GoImIcons.Group, "还没有加入任何群", "点右上角创建或加入")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(groups, key = { it.groupId }) { group ->
                        SwipeToLeave(onLeave = { confirmLeave = group }) {
                            PersonRow(
                                group.displayName,
                                subtitle = "${group.memberCount} 人 · ${group.groupId}",
                                onClick = { onOpenGroup(group) },
                            )
                        }
                    }
                }
            }
        }
    }

    when (createStep) {
        CreateStep.NAME -> TextInputDialog(
            title = "创建群聊",
            message = "输入群名称",
            placeholder = "群名称",
            confirmLabel = "下一步",
            onConfirm = {
                pendingName = it
                createStep = CreateStep.MEMBERS
            },
            onDismiss = { createStep = null },
        )
        CreateStep.MEMBERS -> FriendPickerDialog(
            title = "选择群成员",
            friends = friends,
            allowSkip = true,
            onDone = { picked ->
                createStep = null
                viewModel.create(pendingName, picked, onOpenGroup)
            },
            onDismiss = { createStep = null },
        )
        null -> Unit
    }

    if (showJoin) {
        TextInputDialog(
            title = "加入群聊",
            message = "输入群 ID（如 g_123456）",
            placeholder = "群 ID",
            confirmLabel = "加入",
            onConfirm = {
                showJoin = false
                viewModel.join(it, onOpenGroup)
            },
            onDismiss = { showJoin = false },
        )
    }

    confirmLeave?.let { group ->
        ConfirmDialog(
            text = "确定退出「${group.displayName}」？",
            confirmLabel = "退出",
            onConfirm = {
                confirmLeave = null
                viewModel.leave(group)
            },
            onDismiss = { confirmLeave = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToLeave(onLeave: () -> Unit, content: @Composable () -> Unit) {
    val dismiss = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismiss.currentValue) {
        // 只弹确认，不真正划走；确认后由列表数据驱动移除。
        if (dismiss.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onLeave()
            dismiss.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text("退出", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge)
            }
        },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) { content() }
    }
}

// endregion

// region 好友信息

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendInfoScreen(
    viewModel: FriendInfoViewModel,
    onBack: () -> Unit,
    onSendMessage: (() -> Unit)?,
    onRemoved: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var confirmRemove by remember { mutableStateOf(false) }
    MessageSnackbar(viewModel, snackbar)
    val name = rememberDisplayName(viewModel.uid, viewModel.displayName)
    val isFriend = state.friend != null

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("好友信息") }, navigationIcon = { BackButton(onBack) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                UserAvatar(viewModel.uid, name, size = 80.dp)
                Spacer(Modifier.height(8.dp))
                Text(name, style = MaterialTheme.typography.headlineSmall)
                if (state.loaded && !isFriend) {
                    Text(
                        "你们已不是好友",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SettingsSection(title = "资料") {
                SettingsRow("用户 ID", value = viewModel.uid, icon = GoImIcons.Link, onClick = {
                    clipboard.setText(AnnotatedString(viewModel.uid))
                    viewModel.copied()
                })
            }
            if (onSendMessage != null) {
                SettingsSection(title = null) {
                    SettingsRow("发消息", icon = GoImIcons.Chat, onClick = onSendMessage)
                }
            }
            if (isFriend) {
                SettingsSection(title = null, footer = "删除后对方的好友列表中也会移除你，聊天记录会保留。") {
                    SettingsRow("删除好友", icon = Icons.Filled.Delete, destructive = true, onClick = { confirmRemove = true })
                }
            }
        }
    }

    if (confirmRemove) {
        ConfirmDialog(
            text = "确定删除好友「$name」？",
            confirmLabel = "删除",
            onConfirm = {
                confirmRemove = false
                viewModel.remove(onRemoved)
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

// endregion

// region 群信息

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(viewModel: GroupInfoViewModel, onBack: () -> Unit, onLeft: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val friends by viewModel.friendList.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var showInvite by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    MessageSnackbar(viewModel, snackbar)
    val group = state.group
    val groupName = group?.displayName ?: viewModel.groupId

    fun copyId() {
        clipboard.setText(AnnotatedString(viewModel.groupId))
        viewModel.copied()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("群信息") }, navigationIcon = { BackButton(onBack) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MonogramAvatar(groupName, size = 80.dp)
                Spacer(Modifier.height(8.dp))
                Text(groupName, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${maxOf(state.members.size, group?.memberCount ?: 0)} 位成员",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsSection(title = "群资料") {
                SettingsRow("群 ID", value = viewModel.groupId, icon = GoImIcons.Link, onClick = ::copyId)
                SettingsRow("群主", value = group?.ownerUid?.ifBlank { null } ?: "—", icon = Icons.Filled.Person)
            }
            SettingsSection(title = "成员") {
                if (state.members.isEmpty()) {
                    Text("加载中…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
                state.members.forEach { member ->
                    PersonRow(
                        rememberDisplayName(member.uid),
                        subtitle = if (member.uid == group?.ownerUid) "群主" else "ID：${member.uid}",
                        uid = member.uid,
                    )
                }
            }
            SettingsSection(
                title = null,
                footer = if (state.isOwner) null else "仅群主可以邀请成员",
            ) {
                if (state.isOwner) {
                    SettingsRow("邀请成员", icon = Icons.Filled.Add, onClick = { showInvite = true })
                }
                SettingsRow("退出群聊", icon = GoImIcons.Logout, destructive = true, onClick = { confirmLeave = true })
            }
        }
    }

    if (showInvite) {
        FriendPickerDialog(
            title = "邀请成员",
            friends = friends,
            excluded = state.members.map { it.uid }.toSet(),
            allowSkip = false,
            onDone = {
                showInvite = false
                viewModel.invite(it)
            },
            onDismiss = { showInvite = false },
        )
    }

    if (confirmLeave) {
        ConfirmDialog(
            text = "确定退出「$groupName」？",
            confirmLabel = "退出",
            onConfirm = {
                confirmLeave = false
                viewModel.leave(onLeft)
            },
            onDismiss = { confirmLeave = false },
        )
    }
}

// endregion

// region 共用组件

/** 好友多选（iOS FriendPicker）；[allowSkip] 时可不选人直接完成。 */
@Composable
fun FriendPickerDialog(
    title: String,
    friends: List<Friend>,
    excluded: Set<String> = emptySet(),
    allowSkip: Boolean,
    onDone: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val candidates = remember(friends, excluded) { friends.filter { it.uid !in excluded } }
    val selected = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (candidates.isEmpty()) {
                Text(if (friends.isEmpty()) "暂无好友可选" else "好友都已在群中", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(candidates, key = { it.uid }) { friend ->
                        val checked = friend.uid in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { if (checked) selected.remove(friend.uid) else selected.add(friend.uid) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            UserAvatar(friend.uid, friend.displayName, size = 32.dp, avatarUrl = friend.avatarUrl.ifEmpty { null })
                            Spacer(Modifier.width(10.dp))
                            Text(friend.displayName)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(selected.toList()) }, enabled = allowSkip || selected.isNotEmpty()) {
                Text(if (selected.isEmpty()) "完成" else "完成(${selected.size})")
            }
        },
        dismissButton = {
            if (allowSkip) {
                TextButton(onClick = { onDone(emptyList()) }) { Text("跳过") }
            } else {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun ConfirmDialog(text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
}

// endregion
