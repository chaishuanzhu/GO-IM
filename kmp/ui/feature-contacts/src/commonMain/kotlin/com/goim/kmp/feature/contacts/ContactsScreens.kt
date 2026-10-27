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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.goim.kmp.designsystem.UnreadBadge
import com.goim.kmp.designsystem.PresenceText
import com.goim.kmp.designsystem.UserAvatar
import com.goim.kmp.designsystem.rememberDisplayName
import com.goim.kmp.designsystem.rememberProfile
import com.goim.kmp.designsystem.TextInputDialog
import com.goim.kmp.designsystem.GoImSpacing
import com.goim.kmp.designsystem.GoImSize
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.goim.kmp.designsystem.GoImFormat
import com.goim.kmp.designsystem.SearchEntry
import com.goim.kmp.designsystem.SearchField
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.saveable.rememberSaveable
import com.goim.kmp.designsystem.GoImDimens
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.Surface
import com.goim.kmp.model.Friend
import com.goim.kmp.model.FriendRequest
import com.goim.kmp.model.FriendRequestStatus
import com.goim.kmp.model.UserProfile
import com.goim.kmp.model.friendTitle
import com.goim.kmp.model.Group
import com.goim.kmp.designsystem.GoImTextField
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.foundation.layout.WindowInsets

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
    selected: Boolean = false,
    showPresence: Boolean = false,
) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = {
            UserAvatar(uid, title, size = GoImSize.avatarMd, avatarUrl = avatarUrl.ifEmpty { null }, showPresence = showPresence)
        },
        trailingContent = trailing,
        colors = selectableColors(selected),
        modifier = if (onClick != null) Modifier.selectableRow(selected, onClick) else Modifier,
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
    selection: ContactsSelection? = null,
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
                ShortcutRow(
                    "新的朋友",
                    GoImIcons.PersonAdd,
                    badge = state.pendingCount,
                    selected = selection == ContactsSelection.NewFriends,
                    onClick = onOpenNewFriends,
                )
                ShortcutRow("群组", GoImIcons.Group, badge = 0, selected = selection == ContactsSelection.Groups, onClick = onOpenGroups)
            }
            if (state.loaded && state.sections.isEmpty()) {
                item {
                    EmptyState(
                        GoImIcons.Contacts,
                        "暂无好友",
                        "在「新的朋友」中按昵称、邮箱或用户 ID 搜索添加",
                        Modifier.padding(top = GoImSpacing.s6),
                    )
                }
            }
            state.sections.forEach { section ->
                item(key = "header-${section.letter}") {
                    Text(
                        section.letter,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = GoImSpacing.s4, end = GoImSpacing.s4, top = GoImSpacing.s4, bottom = GoImSpacing.s1),
                    )
                }
                items(section.friends, key = { "friend-${it.uid}" }) { friend ->
                    val profile = rememberProfile(friend.uid)
                    PersonRow(
                        friendTitle(friend.remark, profile?.nickname?.ifBlank { null } ?: friend.displayName),
                        subtitle = friendSignature(friend, profile),
                        uid = friend.uid,
                        avatarUrl = friend.avatarUrl,
                        onClick = { onOpenFriend(friend) },
                        selected = selection == ContactsSelection.Person(friend.uid),
                        showPresence = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortcutRow(title: String, icon: ImageVector, badge: Int, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = {
            TonalIcon(icon, container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
        },
        trailingContent = if (badge > 0) ({ UnreadBadge(badge) }) else null,
        colors = selectableColors(selected),
        modifier = Modifier.selectableRow(selected, onClick),
    )
}

@Composable
private fun selectableColors(selected: Boolean) = ListItemDefaults.colors(
    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
)

/** 与会话列表一致：行两侧留 s2，圆角高亮当前选中项。 */
@Composable
private fun Modifier.selectableRow(selected: Boolean, onClick: () -> Unit) = this
    .padding(horizontal = GoImSpacing.s2)
    .clip(MaterialTheme.shapes.large)
    .semantics { this.selected = selected }
    .clickable(onClick = onClick)

// endregion

// region 新的朋友

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewFriendsScreen(
    viewModel: NewFriendsViewModel,
    onAddFriend: () -> Unit,
    onBack: () -> Unit,
    presentation: Presentation = Presentation.Page,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageSnackbar(viewModel, snackbar)

    Scaffold(

        contentWindowInsets = presentation.contentInsets(),
        topBar = {
            TopAppBar(
                title = { Text("新的朋友") },
                navigationIcon = { NavIcon(presentation, onBack) },
                windowInsets = presentation.topBarInsets(),
                actions = {
                    IconButton(onClick = onAddFriend) { Icon(GoImIcons.PersonAdd, contentDescription = "添加好友") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                SearchEntry(
                    "昵称 / 邮箱 / 用户 ID",
                    onClick = onAddFriend,
                    modifier = Modifier.padding(horizontal = GoImSpacing.s4, vertical = GoImSpacing.s2),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = GoImSpacing.s4))
            }
            if (state.pending.isEmpty() && state.history.isEmpty()) {
                item {
                    EmptyState(
                        GoImIcons.PersonAdd,
                        "暂无新的好友请求",
                        "别人添加你为好友时会显示在这里",
                        Modifier.padding(top = GoImSpacing.s6),
                    )
                }
            }
            if (state.pending.isNotEmpty()) {
                item { SectionHeader("好友请求 · ${state.pending.size}") }
                items(state.pending, key = { "pending-${it.fromUid}" }) { request ->
                    FriendRequestItem(
                        request,
                        enabled = !busy,
                        onDecline = { viewModel.respond(request, accept = false) },
                        onAccept = { viewModel.respond(request, accept = true) },
                    )
                }
            }
            if (state.history.isNotEmpty()) {
                item { SectionHeader("历史记录") }
                items(state.history, key = { "record-${it.fromUid}-${it.toUid}" }) { record ->
                    FriendRequestRecordItem(record, viewModel.selfUid)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GoImSpacing.s4, vertical = GoImSpacing.s2)
            .semantics { heading() },
    )
}

@Composable
private fun FriendRequestItem(request: FriendRequest, enabled: Boolean, onDecline: () -> Unit, onAccept: () -> Unit) {
    val name = rememberDisplayName(request.fromUid, request.displayName)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = GoImSpacing.s4, vertical = GoImSpacing.s3),
        horizontalArrangement = Arrangement.spacedBy(GoImSpacing.s4),
    ) {
        UserAvatar(request.fromUid, name, size = GoImSize.avatarLg, avatarUrl = request.avatarUrl.ifEmpty { null })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GoImSpacing.s1)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (request.createdAt > 0) {
                    Text(
                        GoImFormat.conversationTime(request.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "ID：${request.fromUid} · 请求添加你为好友",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.padding(top = GoImSpacing.s2),
                horizontalArrangement = Arrangement.spacedBy(GoImSpacing.s2),
            ) {
                OutlinedButton(onClick = onDecline, enabled = enabled, modifier = Modifier.weight(1f)) { Text("拒绝") }
                Button(onClick = onAccept, enabled = enabled, modifier = Modifier.weight(1f)) { Text("接受") }
            }
        }
    }
}

@Composable
private fun FriendRequestRecordItem(record: FriendRequest, selfUid: String) {
    val peer = record.peerUid(selfUid)
    val outgoing = record.fromUid == selfUid
    val name = rememberDisplayName(peer, record.nickname ?: peer)
    val status = when (record.status) {
        FriendRequestStatus.ACCEPTED -> "已添加"
        FriendRequestStatus.REJECTED -> if (outgoing) "对方已拒绝" else "已拒绝"
        FriendRequestStatus.PENDING -> "等待验证"
    }
    ListItem(
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                buildString {
                    append(if (outgoing) "你请求添加对方为好友" else "请求添加你为好友")
                    if (record.createdAt > 0) append(" · ").append(GoImFormat.conversationTime(record.createdAt))
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { UserAvatar(peer, name, size = GoImSize.avatarMd, avatarUrl = record.avatarUrl.ifEmpty { null }) },
        trailingContent = {
            Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFriendScreen(
    viewModel: AddFriendViewModel,
    selfUid: String,
    onBack: () -> Unit,
    presentation: Presentation = Presentation.Page,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val friendUids by viewModel.friendUids.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val focus = remember { FocusRequester() }
    MessageSnackbar(viewModel, snackbar)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Scaffold(

        contentWindowInsets = presentation.contentInsets(),
        topBar = {
            TopAppBar(
                title = { Text("添加好友") },
                navigationIcon = { NavIcon(presentation, onBack) },
                windowInsets = presentation.topBarInsets(),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                SearchField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = "昵称 / 邮箱 / 用户 ID",
                    onSearch = viewModel::search,
                    modifier = Modifier
                        .padding(horizontal = GoImSpacing.s4, vertical = GoImSpacing.s2)
                        .focusRequester(focus),
                )
                if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = GoImSpacing.s4))
            }
            when {
                state.error != null -> item {
                    EmptyState(GoImIcons.PersonAdd, "搜索失败", state.error, Modifier.padding(top = GoImSpacing.s6))
                }
                !state.searched -> item {
                    EmptyState(
                        GoImIcons.PersonAdd,
                        "查找用户",
                        "昵称支持模糊匹配，邮箱和用户 ID 需完整输入",
                        Modifier.padding(top = GoImSpacing.s6),
                    )
                }
                !state.searching && state.results.isEmpty() -> item {
                    EmptyState(
                        GoImIcons.PersonAdd,
                        "没有找到用户",
                        "检查拼写，或改用完整的邮箱 / 用户 ID",
                        Modifier.padding(top = GoImSpacing.s6),
                    )
                }
                state.results.isNotEmpty() -> item { SectionHeader("搜索结果") }
            }
            items(state.results, key = { it.uid }) { user ->
                val status = when {
                    user.uid == selfUid -> "自己"
                    user.uid in friendUids -> "已是好友"
                    user.uid in state.sent -> "已发送"
                    else -> null
                }
                UserResultRow(
                    user,
                    status = status,
                    sending = user.uid in state.sending,
                    onAdd = { viewModel.sendRequest(user.uid) },
                )
            }
        }
    }
}

@Composable
private fun UserResultRow(user: UserProfile, status: String?, sending: Boolean, onAdd: () -> Unit) {
    ListItem(
        headlineContent = { Text(user.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("ID：${user.uid}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            UserAvatar(user.uid, user.displayName, size = GoImSize.avatarLg, avatarUrl = user.avatarUrl.ifEmpty { null })
        },
        trailingContent = {
            when {
                status != null -> Text(
                    status,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                sending -> FilledTonalButton(onClick = {}, enabled = false) {
                    CircularProgressIndicator(Modifier.size(GoImSize.iconXs), strokeWidth = GoImSize.strokeThin)
                }
                else -> FilledTonalButton(onClick = onAdd) { Text("添加") }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

// endregion

// region 群组

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(
    viewModel: GroupsViewModel,
    onBack: () -> Unit,
    onOpenGroup: (Group) -> Unit,
    onCreateGroup: () -> Unit,
    onJoinGroup: () -> Unit,
    presentation: Presentation = Presentation.Page,
) {
    val groups by viewModel.groupList.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf<Group?>(null) }
    MessageSnackbar(viewModel, snackbar)

    Scaffold(

        contentWindowInsets = presentation.contentInsets(),
        topBar = {
            TopAppBar(
                title = { Text("群组") },
                navigationIcon = { NavIcon(presentation, onBack) },
                windowInsets = presentation.topBarInsets(),
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.Add, contentDescription = "创建或加入群聊") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("创建群聊") },
                                leadingIcon = { Icon(GoImIcons.Group, contentDescription = null) },
                                onClick = { menuOpen = false; onCreateGroup() },
                            )
                            DropdownMenuItem(
                                text = { Text("加入群聊") },
                                leadingIcon = { Icon(GoImIcons.Link, contentDescription = null) },
                                onClick = { menuOpen = false; onJoinGroup() },
                            )
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
fun CreateGroupScreen(
    viewModel: CreateGroupViewModel,
    onBack: () -> Unit,
    onCreated: (Group) -> Unit,
    presentation: Presentation = Presentation.Page,
) {
    val friends by viewModel.friendList.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var name by rememberSaveable { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    MessageSnackbar(viewModel, snackbar)
    val canCreate = name.isNotBlank() && !busy

    fun submit() {
        if (canCreate) viewModel.create(name.trim(), selected.toList(), onCreated)
    }

    Scaffold(

        contentWindowInsets = presentation.contentInsets(),
        topBar = {
            TopAppBar(
                title = { Text("创建群聊") },
                navigationIcon = { NavIcon(presentation, onBack) },
                windowInsets = presentation.topBarInsets(),
            )
        },
        bottomBar = {
            FormActionBar {
                Button(onClick = ::submit, enabled = canCreate, modifier = Modifier.fillMaxWidth()) {
                    Text(if (selected.isEmpty()) "创建群聊" else "创建群聊（${selected.size + 1} 人）")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                GoImTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "群名称",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = GoImSpacing.s4, vertical = GoImSpacing.s2),
                )
                SectionHeader(if (selected.isEmpty()) "邀请好友（可选）" else "邀请好友 · 已选 ${selected.size}")
            }
            if (friends.isEmpty()) {
                item {
                    Text(
                        "暂无好友，创建后可在群信息中邀请成员",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = GoImSpacing.s4, vertical = GoImSpacing.s2),
                    )
                }
            }
            items(friends, key = { it.uid }) { friend ->
                val checked = friend.uid in selected
                ListItem(
                    headlineContent = { Text(friend.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        UserAvatar(friend.uid, friend.displayName, size = GoImSize.avatarMd, avatarUrl = friend.avatarUrl.ifEmpty { null })
                    },
                    trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.toggleable(value = checked, role = Role.Checkbox) {
                        if (it) selected.add(friend.uid) else selected.remove(friend.uid)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinGroupScreen(
    viewModel: JoinGroupViewModel,
    onBack: () -> Unit,
    onJoined: (Group) -> Unit,
    presentation: Presentation = Presentation.Page,
) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val focus = remember { FocusRequester() }
    var groupId by rememberSaveable { mutableStateOf("") }
    MessageSnackbar(viewModel, snackbar)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val canJoin = groupId.isNotBlank() && !busy

    fun submit() {
        if (canJoin) viewModel.join(groupId.trim(), onJoined)
    }

    Scaffold(

        contentWindowInsets = presentation.contentInsets(),
        topBar = {
            TopAppBar(
                title = { Text("加入群聊") },
                navigationIcon = { NavIcon(presentation, onBack) },
                windowInsets = presentation.topBarInsets(),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(
                Modifier.widthIn(max = GoImDimens.formMaxWidth).fillMaxWidth().padding(GoImSpacing.s6),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(GoImSpacing.s4),
            ) {
                TonalIcon(
                    GoImIcons.Group,
                    size = GoImSize.iconXl,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "向群成员索要群 ID，群信息页可以复制",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                GoImTextField(
                    value = groupId,
                    onValueChange = { groupId = it },
                    placeholder = "群 ID，如 g_123456",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Button(onClick = ::submit, enabled = canJoin, modifier = Modifier.fillMaxWidth()) {
                    Text("加入群聊")
                }
            }
        }
    }
}

/** 页面底部的主操作区：避让导航栏与键盘。 */
@Composable
private fun FormActionBar(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = GoImSpacing.s4, vertical = GoImSpacing.s3),
        ) { content() }
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
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(end = GoImSpacing.s6),
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
    presentation: Presentation = Presentation.Page,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var confirmRemove by remember { mutableStateOf(false) }
    var editRemark by remember { mutableStateOf(false) }
    MessageSnackbar(viewModel, snackbar)
    val name = rememberDisplayName(viewModel.uid, viewModel.displayName)
    val friend = state.friend
    val isFriend = friend != null
    val isSelf = viewModel.isSelf
    val remark = friend?.remark.orEmpty()
    val title = friendTitle(remark, name)
    val profile = rememberProfile(viewModel.uid)
    val signature = friend?.let { friendSignature(it, profile) } ?: profile?.signature?.ifBlank { null }

    Scaffold(

        contentWindowInsets = presentation.contentInsets(),
        topBar = {
            TopAppBar(
                title = { Text("好友信息") },
                navigationIcon = { NavIcon(presentation, onBack) },
                windowInsets = presentation.topBarInsets(),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(
                Modifier.fillMaxWidth().padding(vertical = GoImSpacing.s4),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(GoImSpacing.s1),
            ) {
                UserAvatar(viewModel.uid, remark.ifBlank { name }, size = GoImSize.avatar2xl, showPresence = true)
                Spacer(Modifier.height(GoImSpacing.s2))
                Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                PresenceText(viewModel.uid)
                if (signature != null) {
                    Text(
                        signature,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = GoImSpacing.s6),
                    )
                }
            }
            SettingsSection(title = "资料") {
                if (isFriend) {
                    SettingsRow(
                        "备注名",
                        value = remark.ifBlank { "未设置" },
                        icon = Icons.Filled.Edit,
                        onClick = { editRemark = true },
                    )
                }
                SettingsRow("用户 ID", value = viewModel.uid, icon = GoImIcons.Link, onClick = {
                    clipboard.setText(AnnotatedString(viewModel.uid))
                    viewModel.copied()
                })
            }
            if (state.loaded && !isFriend && !isSelf) {
                SettingsSection(title = null) {
                    when (state.pending) {
                        PendingRequest.NONE -> SettingsRow(
                            "添加好友",
                            icon = GoImIcons.PersonAdd,
                            onClick = if (busy) null else viewModel::addFriend,
                        )
                        PendingRequest.SENT -> SettingsRow("添加好友", value = "已发送请求，等待对方验证", icon = GoImIcons.PersonAdd)
                        PendingRequest.RECEIVED -> SettingsRow(
                            "通过好友验证",
                            value = "对方请求添加你为好友",
                            icon = GoImIcons.PersonAdd,
                            onClick = if (busy) null else viewModel::accept,
                        )
                    }
                }
            }
            if (onSendMessage != null && !isSelf) {
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
            text = "确定删除好友「$title」？",
            confirmLabel = "删除",
            onConfirm = {
                confirmRemove = false
                viewModel.remove(onRemoved)
            },
            onDismiss = { confirmRemove = false },
        )
    }
    if (editRemark) {
        TextInputDialog(
            title = "设置备注名",
            message = null,
            initial = remark,
            placeholder = "留空则清除",
            confirmLabel = "保存",
            validate = { if (it.trim().length > MAX_REMARK) "备注名最多 $MAX_REMARK 个字" else null },
            onConfirm = {
                editRemark = false
                if (it.trim() != remark) viewModel.setRemark(it.trim())
            },
            onDismiss = { editRemark = false },
        )
    }
}

/** 好友的个性签名：优先用资料缓存（更新更及时），没有时用好友列表里的。 */
private fun friendSignature(friend: Friend, profile: UserProfile?): String? =
    profile?.signature?.ifBlank { null } ?: friend.signature.ifBlank { null }

private const val MAX_REMARK = 32
private const val MAX_GROUP_TEXT = 32

// endregion

// region 群信息

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    viewModel: GroupInfoViewModel,
    onBack: () -> Unit,
    onLeft: () -> Unit,
    onOpenMember: (uid: String, name: String) -> Unit = { _, _ -> },
    presentation: Presentation = Presentation.Page,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val friends by viewModel.friendList.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var showInvite by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(false) }
    var editAlias by remember { mutableStateOf(false) }
    MessageSnackbar(viewModel, snackbar)
    val group = state.group
    val groupName = group?.displayName ?: viewModel.groupId

    fun copyId() {
        clipboard.setText(AnnotatedString(viewModel.groupId))
        viewModel.copied()
    }

    Scaffold(

        contentWindowInsets = presentation.contentInsets(),
        topBar = {
            TopAppBar(
                title = { Text("群信息") },
                navigationIcon = { NavIcon(presentation, onBack) },
                windowInsets = presentation.topBarInsets(),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(
                Modifier.fillMaxWidth().padding(vertical = GoImSpacing.s4),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(GoImSpacing.s1),
            ) {
                MonogramAvatar(groupName, size = GoImSize.avatar2xl)
                Spacer(Modifier.height(GoImSpacing.s2))
                Text(groupName, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${maxOf(state.members.size, group?.memberCount ?: 0)} 位成员",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsSection(title = "群资料") {
                SettingsRow(
                    "群名称",
                    value = groupName,
                    icon = Icons.Filled.Edit,
                    onClick = if (state.isOwner) ({ editName = true }) else null,
                )
                SettingsRow(
                    "我在本群的昵称",
                    value = viewModel.myAlias.ifBlank { "未设置" },
                    icon = Icons.Filled.Face,
                    onClick = { editAlias = true },
                )
                SettingsRow("群 ID", value = viewModel.groupId, icon = GoImIcons.Link, onClick = ::copyId)
                SettingsRow("群主", value = group?.ownerUid?.ifBlank { null } ?: "—", icon = Icons.Filled.Person)
            }
            SettingsSection(title = "成员") {
                if (state.members.isEmpty()) {
                    Text("加载中…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(GoImSpacing.s4))
                }
                state.members.forEach { member ->
                    val nickname = rememberDisplayName(member.uid)
                    val profile = rememberProfile(member.uid)
                    PersonRow(
                        friendTitle(member.alias, nickname),
                        subtitle = if (member.uid == group?.ownerUid) "群主" else profile?.signature?.ifBlank { null },
                        uid = member.uid,
                        onClick = {
                            if (member.uid == viewModel.selfUid) editAlias = true else onOpenMember(member.uid, nickname)
                        },
                        showPresence = true,
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

    if (editName) {
        TextInputDialog(
            title = "修改群名称",
            message = null,
            initial = group?.name.orEmpty(),
            confirmLabel = "保存",
            validate = {
                when {
                    it.isBlank() -> "群名称不能为空"
                    it.trim().length > MAX_GROUP_TEXT -> "群名称最多 $MAX_GROUP_TEXT 个字"
                    else -> null
                }
            },
            onConfirm = {
                editName = false
                if (it.trim() != group?.name) viewModel.rename(it.trim())
            },
            onDismiss = { editName = false },
        )
    }
    if (editAlias) {
        TextInputDialog(
            title = "我在本群的昵称",
            message = "群内所有成员可见",
            initial = viewModel.myAlias,
            placeholder = "留空则清除",
            confirmLabel = "保存",
            validate = { if (it.trim().length > MAX_GROUP_TEXT) "群昵称最多 $MAX_GROUP_TEXT 个字" else null },
            onConfirm = {
                editAlias = false
                if (it.trim() != viewModel.myAlias) viewModel.setMyAlias(it.trim())
            },
            onDismiss = { editAlias = false },
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
                                .padding(vertical = GoImSpacing.s2),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(GoImSpacing.s2))
                            UserAvatar(friend.uid, friend.displayName, size = GoImSize.avatarSm, avatarUrl = friend.avatarUrl.ifEmpty { null })
                            Spacer(Modifier.width(GoImSpacing.s3))
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

/** 页面的呈现方式：宽屏时同一页面可内嵌在右栏，或放进弹窗 / 侧边面板。 */
enum class Presentation {
    /** 全屏二级页，左上角返回。 */
    Page,

    /** 宽屏右栏，由左侧列表切换，无导航按钮。 */
    Pane,

    /** 弹窗或侧边面板，左上角关闭。 */
    Overlay,
}

/** 宽屏通讯录右栏正在显示的内容，用于高亮左侧对应行。 */
sealed interface ContactsSelection {
    data object NewFriends : ContactsSelection
    data object Groups : ContactsSelection
    data class Person(val uid: String) : ContactsSelection
}

@Composable
private fun NavIcon(presentation: Presentation, onBack: () -> Unit) {
    when (presentation) {
        Presentation.Page -> IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Presentation.Overlay -> IconButton(onClick = onBack) { Icon(Icons.Filled.Close, contentDescription = "关闭") }
        Presentation.Pane -> Unit
    }
}

/** 右栏、弹窗、侧边面板都不贴窗口边缘，不再为状态栏让位。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Presentation.topBarInsets() = if (this == Presentation.Page) TopAppBarDefaults.windowInsets else WindowInsets(0)

@Composable
private fun Presentation.contentInsets() = if (this == Presentation.Page) ScaffoldDefaults.contentWindowInsets else WindowInsets(0)

// endregion
