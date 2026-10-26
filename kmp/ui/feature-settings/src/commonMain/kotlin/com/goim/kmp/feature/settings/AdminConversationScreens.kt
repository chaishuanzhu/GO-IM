package com.goim.kmp.feature.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goim.kmp.designsystem.ConfirmDialog
import com.goim.kmp.designsystem.EmptyState
import com.goim.kmp.designsystem.GoImDimens
import com.goim.kmp.designsystem.GoImFormat
import com.goim.kmp.designsystem.GoImIcons
import com.goim.kmp.designsystem.GoImSize
import com.goim.kmp.designsystem.GoImSpacing
import com.goim.kmp.designsystem.MonogramAvatar
import com.goim.kmp.designsystem.UserAvatar
import com.goim.kmp.model.AdminConversation
import com.goim.kmp.model.AdminMessage
import com.goim.kmp.model.AdminSender
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.MessagePreview
import com.goim.kmp.model.MsgType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** 管理后台：[userName] 参与过的会话，点一个进入只读消息列表。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminConversationsScreen(
    viewModel: AdminConversationsViewModel,
    userName: String,
    onBack: () -> Unit,
    onOpen: (AdminConversation) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(userName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("会话", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.items.isEmpty() && state.error != null -> ErrorState(state.error!!)
                state.items.isEmpty() && (state.loading || !state.loaded) -> LoadingState()
                state.items.isEmpty() -> EmptyState(GoImIcons.Chat, "没有会话", "该用户还没有收发过消息")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.items, key = { "${it.chatType.value}:${it.peer}" }) { conversation ->
                        ConversationRow(conversation, onClick = { onOpen(conversation) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: AdminConversation, onClick: () -> Unit) {
    val title = conversation.displayTitle
    val isGroup = conversation.chatType == ChatType.GROUP
    val last = conversation.lastMessage
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = "查看消息", onClick = onClick),
        leadingContent = {
            if (isGroup) MonogramAvatar(title, size = GoImSize.avatarMd) else UserAvatar(conversation.peer, title, size = GoImSize.avatarMd, avatarUrl = "")
        },
        headlineContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(GoImSpacing.s2), verticalAlignment = Alignment.CenterVertically) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (isGroup) {
                    Tag("群聊", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        },
        supportingContent = {
            Text(
                if (isGroup) "${last.fromUid}：${last.preview}" else last.preview,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(GoImFormat.conversationTime(last.timestampMs), style = MaterialTheme.typography.labelSmall)
                Text(
                    "${conversation.messageCount} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** 绘制一条消息气泡：[outgoing] 时靠右，长按调用 onLongPress。 */
typealias AdminBubble = @Composable (
    message: AdminMessage,
    sender: AdminSender?,
    outgoing: Boolean,
    maxBubbleWidth: Dp,
    onLongPress: () -> Unit,
) -> Unit

/**
 * 管理后台：只读查看会话消息，没有输入栏。[selfUid]（被查看的用户，名称 [selfName]）的消息靠右，其他人靠左；
 * 长按消息可从服务器删除。气泡由 [bubble] 绘制，与聊天页保持一致。
 * [title] 在服务端未返回群名 / 对方昵称时使用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminChatScreen(
    viewModel: AdminChatViewModel,
    title: String,
    isGroup: Boolean,
    selfUid: String,
    selfName: String,
    onBack: () -> Unit,
    bubble: AdminBubble,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val shownTitle = state.title.ifEmpty { title }
    val subtitle = if (isGroup) "群聊 · 全部成员的消息" else "${state.senders[selfUid]?.nickname?.takeIf { it.isNotBlank() } ?: selfName} 的单聊"
    var deleting by remember { mutableStateOf<AdminMessage?>(null) }
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
                title = {
                    Column {
                        Text(shownTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.messages.isEmpty() && state.error != null -> ErrorState(state.error!!)
                state.messages.isEmpty() && (state.loading || !state.loaded) -> LoadingState()
                state.messages.isEmpty() -> EmptyState(GoImIcons.Chat, "没有消息")
                else -> MessageList(
                    state,
                    selfUid,
                    bubble,
                    onLoadOlder = viewModel::loadOlder,
                    onHighlightShown = viewModel::clearHighlight,
                    onLongPress = { deleting = it },
                )
            }
        }
    }

    deleting?.let { message ->
        ConfirmDialog(
            title = "删除消息？",
            text = "将从服务器删除这条消息，已收到的客户端本地记录不受影响。",
            confirmLabel = "删除",
            destructive = true,
            icon = Icons.Filled.Delete,
            onConfirm = {
                viewModel.delete(message)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun MessageList(
    state: AdminChatUiState,
    selfUid: String,
    bubble: AdminBubble,
    onLoadOlder: () -> Unit,
    onHighlightShown: () -> Unit,
    onLongPress: (AdminMessage) -> Unit,
) {
    val listState = rememberLazyListState()
    val targetIndex = state.highlightId?.let { id -> state.messages.indexOfFirst { it.msgId == id } } ?: -1
    // 定位：滚到目标消息，高亮 2 秒（同聊天页搜索跳转）
    LaunchedEffect(state.highlightId, targetIndex >= 0) {
        if (targetIndex >= 0) {
            listState.scrollToItem(targetIndex)
            delay(HIGHLIGHT_MILLIS)
            onHighlightShown()
        }
    }
    // reverseLayout：index 0 在底部，滚到顶部附近时加载更早的消息
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 3
        }.distinctUntilChanged().filter { it }.collect { onLoadOlder() }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxBubbleWidth = maxWidth * GoImDimens.BUBBLE_MAX_WIDTH_RATIO
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = GoImSpacing.s3, vertical = GoImSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(GoImSpacing.s2),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.messages, key = { it.msgId }) { message ->
                val tint by animateColorAsState(
                    if (message.msgId == state.highlightId) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent,
                )
                val notice = when {
                    message.recalled -> "${senderName(message.fromUid, state.senders)} 撤回了一条消息"
                    message.msgType == MsgType.Text -> MessagePreview.systemNotice(message.content)
                    else -> null
                }
                Box(Modifier.fillMaxWidth().background(tint, MaterialTheme.shapes.medium)) {
                    if (notice != null) {
                        CenterNotice(notice)
                    } else {
                        bubble(message, state.senders[message.fromUid], message.fromUid == selfUid, maxBubbleWidth) { onLongPress(message) }
                    }
                }
            }
            if (state.loading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(GoImSpacing.s2), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(GoImSize.iconSm), strokeWidth = GoImSize.strokeThin)
                    }
                }
            } else if (state.endReached) {
                item(key = "end") { CenterNotice("没有更早的消息了") }
            }
        }
    }
}

private const val HIGHLIGHT_MILLIS = 2_000L

private fun senderName(uid: String, senders: Map<String, AdminSender>): String =
    senders[uid]?.nickname?.takeIf { it.isNotBlank() } ?: uid

@Composable
private fun CenterNotice(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = GoImSpacing.s1), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = GoImSpacing.s3, vertical = GoImSpacing.s1),
            )
        }
    }
}
