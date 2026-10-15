package com.goim.kmp.feature.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goim.kmp.designsystem.EmptyState
import com.goim.kmp.designsystem.GoImFormat
import com.goim.kmp.designsystem.GoImIcons
import com.goim.kmp.designsystem.MonogramAvatar
import com.goim.kmp.designsystem.UserAvatar
import com.goim.kmp.designsystem.rememberDisplayName
import com.goim.kmp.designsystem.TextInputDialog
import com.goim.kmp.designsystem.UnreadBadge
import com.goim.kmp.designsystem.title
import com.goim.kmp.model.ConnectionState
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.Conversation
import com.goim.kmp.model.MessagePreview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    viewModel: ConversationListViewModel,
    onOpenChat: (Conversation) -> Unit,
    /** 双栏布局下右侧正在显示的会话，高亮该行。 */
    selectedId: String? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showNewChat by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("聊天")
                        if (state.loaded && state.connectionState != ConnectionState.CONNECTED) {
                            Text(
                                state.connectionState.title,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewChat = true }) {
                Icon(Icons.Filled.Edit, contentDescription = "新建对话")
            }
        },
    ) { padding ->
        if (state.loaded && state.conversations.isEmpty()) {
            EmptyState(GoImIcons.Chat, "暂无会话", "点按右下角按钮开始聊天", Modifier.padding(padding))
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 88.dp,
                ),
            ) {
                items(state.conversations, key = { it.id }, contentType = { "conversation" }) { conversation ->
                    SwipeToDeleteRow(onDelete = { viewModel.delete(conversation.id) }) {
                        ConversationRow(
                            conversation,
                            selected = conversation.id == selectedId,
                            onClick = { onOpenChat(conversation) },
                            onDelete = { viewModel.delete(conversation.id) },
                        )
                    }
                }
            }
        }
    }

    if (showNewChat) {
        TextInputDialog(
            title = "新建对话",
            message = "输入对方用户 ID",
            placeholder = "用户 ID",
            confirmLabel = "打开",
            onConfirm = {
                showNewChat = false
                onOpenChat(viewModel.newDirectChat(it))
            },
            onDismiss = { showNewChat = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val dismiss = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onDelete() },
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) { content() }
    }
}

/** 长按（触屏）或右键（桌面）弹出菜单。 */
@Composable
private fun ConversationRow(conversation: Conversation, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ConversationRowContent(
            conversation,
            selected,
            Modifier
                .semantics { this.selected = selected }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                event.changes.forEach { it.consume() }
                                menuOpen = true
                            }
                        }
                    }
                }
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }, onLongClickLabel = "更多操作"),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("删除会话", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun ConversationRowContent(conversation: Conversation, selected: Boolean, modifier: Modifier) {
    val unread = conversation.unreadCount > 0
    val preview = MessagePreview.sanitizedListPreview(conversation.lastMessagePreview)
    val unreadLabel = if (unread) "，${conversation.unreadCount} 条未读" else ""
    val isGroup = conversation.chatType == ChatType.GROUP
    val title = if (isGroup) conversation.title else rememberDisplayName(conversation.peerOrGroupId, conversation.title)
    ListItem(
        headlineContent = {
            Text(
                title,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                preview,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            if (isGroup) MonogramAvatar(title) else UserAvatar(conversation.peerOrGroupId, title)
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    GoImFormat.conversationTime(conversation.lastMessageAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UnreadBadge(conversation.unreadCount)
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(MaterialTheme.shapes.large)
            .then(modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title$unreadLabel，$preview"
            },
    )
}
