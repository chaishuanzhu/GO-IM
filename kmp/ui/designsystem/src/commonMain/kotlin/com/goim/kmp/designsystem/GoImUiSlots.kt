package com.goim.kmp.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.Conversation
import com.goim.kmp.model.Message
import com.goim.kmp.model.MessageStatus
import com.goim.kmp.model.MsgType

/** 会话列表行的稳定 UI 模型（宿主 Slot 勿直接依赖 DB / proto）。 */
@Immutable
data class ConversationUi(
    val id: String,
    val title: String,
    val preview: String,
    val unreadCount: Int,
    val selected: Boolean,
    val isGroup: Boolean,
    val peerOrGroupId: String,
    val updatedAt: Long,
)

fun Conversation.toUi(selected: Boolean, titleOverride: String? = null) = ConversationUi(
    id = id,
    title = titleOverride ?: title,
    preview = lastMessagePreview,
    unreadCount = unreadCount,
    selected = selected,
    isGroup = chatType == ChatType.GROUP,
    peerOrGroupId = peerOrGroupId,
    updatedAt = lastMessageAt,
)

/** 聊天气泡的稳定 UI 模型。 */
@Immutable
data class MessageUi(
    val id: String,
    val clientSeq: Long,
    val conversationId: String,
    val fromUid: String,
    val text: String,
    val outgoing: Boolean,
    val msgType: MsgType,
    val status: MessageStatus,
    val timestampMs: Long,
    val contentJson: String,
)

fun Message.toUi(selfUid: String = "") = MessageUi(
    id = id,
    clientSeq = clientSeq,
    conversationId = conversationId,
    fromUid = fromUid,
    text = content,
    outgoing = isOutgoing || (selfUid.isNotEmpty() && fromUid == selfUid),
    msgType = msgType,
    status = status,
    timestampMs = timestampMs,
    contentJson = content,
)

@Immutable
data class ChatTopBarUi(
    val title: String,
    val subtitle: String?,
    val isGroup: Boolean,
    val peerOrGroupId: String,
    val showBack: Boolean,
    val showInfo: Boolean,
)

@Immutable
data class ConversationRowActions(
    val onClick: () -> Unit,
    val onDelete: () -> Unit,
)

@Immutable
data class MessageBubbleContext(
    val maxWidth: Dp,
    val onRetry: () -> Unit,
)

@Immutable
data class ChatTopBarActions(
    val onBack: (() -> Unit)?,
    val onOpenInfo: (() -> Unit)?,
)

/**
 * Full SDK 可替换插槽。字段为 null 时各 feature 使用内置默认 Composable。
 *
 * 典型用法：在 [com.goim.sdk.full.GoImNavHost] 传入自定义 lambda。
 */
@Immutable
data class GoImUiSlots(
    val conversationRow: (@Composable (ConversationUi, ConversationRowActions) -> Unit)? = null,
    val messageBubble: (@Composable (MessageUi, MessageBubbleContext) -> Unit)? = null,
    val chatTopBar: (@Composable (ChatTopBarUi, ChatTopBarActions) -> Unit)? = null,
    val emptyConversations: (@Composable () -> Unit)? = null,
    val emptyChat: (@Composable () -> Unit)? = null,
    val composerTrailing: (@Composable () -> Unit)? = null,
)

val LocalGoImUiSlots = staticCompositionLocalOf { GoImUiSlots() }

/** 桌面自定义标题栏高度；由宿主 / Full 导航读取。 */
class TitleBarInset(val height: androidx.compose.ui.unit.Dp) {
    var claimed by androidx.compose.runtime.mutableStateOf(false)
}

val LocalTitleBarInset = staticCompositionLocalOf<TitleBarInset?> { null }
