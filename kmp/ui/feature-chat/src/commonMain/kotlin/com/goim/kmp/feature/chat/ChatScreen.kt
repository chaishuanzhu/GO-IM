package com.goim.kmp.feature.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.style.TextOverflow
import com.goim.kmp.designsystem.MonogramAvatar
import com.goim.kmp.designsystem.PresenceText
import com.goim.kmp.designsystem.UserAvatar
import com.goim.kmp.designsystem.rememberDisplayName
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goim.kmp.designsystem.GoImDimens
import com.goim.kmp.designsystem.GoImFormat
import com.goim.kmp.designsystem.GoImIcons
import com.goim.kmp.designsystem.GoImTheme
import com.goim.kmp.designsystem.title
import com.goim.kmp.designsystem.GoImSpacing
import com.goim.kmp.designsystem.GoImSize
import com.goim.kmp.domain.FileRepository
import com.goim.kmp.domain.StickerRepository
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.ConnectionState
import com.goim.kmp.model.FileMeta
import com.goim.kmp.model.Message
import com.goim.kmp.model.MessagePreview
import com.goim.kmp.model.MessageStatus
import com.goim.kmp.model.MsgType
import com.goim.kmp.model.StickerRef
import com.goim.kmp.platform.VoicePlayer
import com.goim.kmp.platform.createVoicePlayer
import com.goim.kmp.platform.createVoiceRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel, onBack: (() -> Unit)?, onOpenInfo: (() -> Unit)? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val conversation = viewModel.conversation
    val platformContext = rememberPlatformContext()
    val player = remember { createVoicePlayer(platformContext) }
    val recorder = remember { createVoiceRecorder(platformContext) }
    var preview by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var filePreview by remember { mutableStateOf<FilePreviewRequest?>(null) }
    var dropHover by remember { mutableStateOf(false) }
    var collapseComposer by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dropModifier = rememberFileDropModifier(
        onHover = { dropHover = it },
        onDrop = { files -> viewModel.media?.sendDropped(files) },
    )
    DisposableEffect(Unit) {
        onDispose {
            player.stop()
            recorder.cancel()
        }
    }
    val bubbles = remember(viewModel, player) {
        BubbleEnv(
            viewModel.media?.files,
            viewModel.media?.stickers,
            player,
            selfName = viewModel.selfName,
            onPreview = { model, local -> preview = model to local },
            onOpenFile = { path, meta ->
                val kind = if (inAppFilePreview) filePreviewKind(meta) else null
                if (kind != null) filePreview = FilePreviewRequest(path, meta, kind) else openWithSystem(path, meta.name)
            },
            onError = viewModel::reportError,
        )
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    val isGroup = conversation.chatType == ChatType.GROUP
    val title = if (isGroup) conversation.title else rememberDisplayName(conversation.peerOrGroupId, conversation.title)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isGroup) {
                            MonogramAvatar(title, size = GoImSize.avatarMd)
                        } else {
                            UserAvatar(conversation.peerOrGroupId, title, size = GoImSize.avatarMd, showPresence = true)
                        }
                        Spacer(Modifier.width(GoImSpacing.s3))
                        Column {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val subtitle = when {
                                state.connectionState != ConnectionState.CONNECTED -> state.connectionState.title
                                conversation.chatType == ChatType.GROUP -> "群聊"
                                else -> null
                            }
                            if (subtitle != null) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                PresenceText(conversation.peerOrGroupId, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                    }
                },
                actions = {
                    if (onOpenInfo != null) {
                        if (conversation.chatType == ChatType.GROUP) {
                            IconButton(onClick = onOpenInfo) { Icon(GoImIcons.Group, contentDescription = "群信息") }
                        } else {
                            IconButton(onClick = onOpenInfo) { Icon(Icons.Filled.Person, contentDescription = "好友信息") }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        // 底部安全区由输入栏自己处理（背景铺到屏幕底边），这里只让出顶栏
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .then(if (viewModel.media != null) dropModifier else Modifier),
        ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            MessageList(
                state = state,
                onLoadOlder = viewModel::loadOlder,
                onRetry = viewModel::retry,
                onHighlightShown = viewModel::clearHighlight,
                bubbles = bubbles,
                modifier = Modifier
                    .weight(1f)
                    // 点消息列表空白处收起键盘和表情 / 语音 / 更多面板；气泡等可点击元素会先消费点击
                    .pointerInput(Unit) {
                        detectTapGestures {
                            focusManager.clearFocus()
                            keyboard?.hide()
                            collapseComposer++
                        }
                    },
            )
            ChatComposer(
                collapseRequests = collapseComposer,
                draft = draft,
                onDraftChange = viewModel::onDraftChange,
                onSend = viewModel::send,
                media = viewModel.media,
                recorder = recorder,
                isGroup = conversation.chatType == ChatType.GROUP,
                onAppend = viewModel::appendToDraft,
                onMention = viewModel::insertMention,
                onError = viewModel::reportError,
            )
        }
            if (dropHover) DropOverlay()
        }
    }
    preview?.let { (model, local) -> ImagePreviewDialog(model, local, onDismiss = { preview = null }) }
    filePreview?.let { FilePreviewDialog(it, onDismiss = { filePreview = null }) }
}

@Composable
private fun DropOverlay() {
    Box(
        Modifier
            .fillMaxSize()
            .padding(GoImSpacing.s3)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f), MaterialTheme.shapes.extraLarge)
            .border(GoImSize.strokeThin, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraLarge),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(GoImSpacing.s2)) {
            Icon(GoImIcons.Upload, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(GoImSize.iconLg))
            Text("松开发送文件", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** 气泡渲染附件所需的依赖；[files] / [stickers] 为空时附件只显示文字摘要。 */
internal class BubbleEnv(
    val files: FileRepository?,
    val stickers: StickerRepository?,
    val player: VoicePlayer,
    val selfName: String,
    val onPreview: (model: String, localPath: String?) -> Unit,
    val onOpenFile: (path: String, meta: FileMeta) -> Unit,
    val onError: (String) -> Unit,
    /** 只读列表用长按做别的操作，文字不进入选择模式。 */
    val selectableText: Boolean = true,
)

@Composable
private fun MessageList(
    state: ChatUiState,
    onLoadOlder: () -> Unit,
    onRetry: (Message) -> Unit,
    onHighlightShown: () -> Unit,
    bubbles: BubbleEnv,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val newestFirst = remember(state.messages) { state.messages.asReversed() }
    val newest = newestFirst.firstOrNull()
    val targetIndex = state.highlightId?.let { id -> newestFirst.indexOfFirst { it.id == id } } ?: -1

    // 搜索跳转：目标进入窗口后滚过去，高亮 2 秒。
    LaunchedEffect(state.highlightId, targetIndex >= 0) {
        if (targetIndex >= 0) {
            listState.scrollToItem(targetIndex)
            delay(HIGHLIGHT_MILLIS)
            onHighlightShown()
        }
    }

    // reverseLayout：index 0 在底部。新消息到达时，若在底部附近或是自己发的，滚到底。
    LaunchedEffect(newest?.id) {
        if (newest != null && (listState.firstVisibleItemIndex <= 1 || newest.isOutgoing)) {
            listState.animateScrollToItem(0)
        }
    }
    // 滚到顶部附近触发加载更早。
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 3
        }.distinctUntilChanged().filter { it }.collect { onLoadOlder() }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * GoImDimens.BUBBLE_MAX_WIDTH_RATIO
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = GoImSpacing.s3, vertical = GoImSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(GoImSpacing.s2),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(newestFirst, key = { it.id }, contentType = { it.msgType }) { message ->
                val highlighted = message.id == state.highlightId
                val tint by animateColorAsState(
                    if (highlighted) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent,
                )
                Box(Modifier.fillMaxWidth().background(tint, MaterialTheme.shapes.medium)) {
                    MessageRow(message, maxBubbleWidth, onRetry, bubbles)
                }
            }
            if (state.isLoadingHistory) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(GoImSpacing.s2), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(GoImSize.iconSm), strokeWidth = GoImSize.strokeThin)
                    }
                }
            }
        }
    }
}

private const val HIGHLIGHT_MILLIS = 2_000L

@Composable
private fun MessageRow(message: Message, maxBubbleWidth: Dp, onRetry: (Message) -> Unit, bubbles: BubbleEnv) {
    val notice = if (message.msgType == MsgType.Text) MessagePreview.systemNotice(message.content) else null
    when {
        message.status == MessageStatus.RECALLED -> CenterNotice("已撤回")
        notice != null -> CenterNotice(notice)
        else -> Bubble(message, maxBubbleWidth, onRetry, bubbles)
    }
}

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

@Composable
private fun Bubble(message: Message, maxBubbleWidth: Dp, onRetry: (Message) -> Unit, bubbles: BubbleEnv) {
    val senderName = if (message.isOutgoing) bubbles.selfName else rememberDisplayName(message.fromUid)
    BubbleRow(message, senderName, avatarUrl = null, maxBubbleWidth, bubbles, onRetry = onRetry)
}

/** 头像 + 昵称时间 + 气泡；[avatarUrl] 为空时从资料缓存读取头像。 */
@Composable
internal fun BubbleRow(
    message: Message,
    senderName: String,
    avatarUrl: String?,
    maxBubbleWidth: Dp,
    bubbles: BubbleEnv,
    onRetry: (Message) -> Unit = {},
    onLongPress: (() -> Unit)? = null,
) {
    val outgoing = message.isOutgoing
    val time = GoImFormat.messageTime(message.timestampMs)
    val meta = if (outgoing) time else "$senderName · $time"

    val description = remember(message.content, message.status, message.msgType, time, senderName) {
        buildString {
            append(if (outgoing) "我" else senderName).append("，")
            append(if (message.msgType == MsgType.Text) message.content else MessagePreview.listPreview(message)).append("，")
            append(time)
            when (message.status) {
                MessageStatus.SENDING -> append("，发送中")
                MessageStatus.FAILED -> append("，发送失败")
                else -> Unit
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(GoImSpacing.s2, if (outgoing) Alignment.End else Alignment.Start),
        verticalAlignment = Alignment.Top,
    ) {
        if (!outgoing) UserAvatar(message.fromUid, senderName, size = GoImDimens.messageAvatarSize, avatarUrl = avatarUrl)
        Column(
            Modifier.weight(1f, fill = false),
            horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
        ) {
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = GoImSpacing.s2, vertical = GoImSpacing.s1),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (outgoing) {
                    when (message.status) {
                        MessageStatus.SENDING -> CircularProgressIndicator(Modifier.size(GoImSize.iconXs), strokeWidth = GoImSize.strokeThin)
                        MessageStatus.FAILED -> Icon(
                            Icons.Filled.Warning,
                            contentDescription = "重试发送",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clickable(role = Role.Button, onClickLabel = "重试") { onRetry(message) }
                                .size(GoImSize.iconSm),
                        )
                        else -> Unit
                    }
                    Spacer(Modifier.width(GoImSpacing.s2))
                }
                Box(if (onLongPress != null) Modifier.onLongPress(onLongPress) else Modifier) {
                    BubbleBody(message, maxBubbleWidth, bubbles)
                }
            }
        }
        if (outgoing) UserAvatar(message.fromUid, senderName, size = GoImDimens.messageAvatarSize, avatarUrl = avatarUrl)
    }
}

@Composable
private fun BubbleBody(message: Message, maxBubbleWidth: Dp, bubbles: BubbleEnv) {
    val colors = GoImTheme.chatColors
    val outgoing = message.isOutgoing
    val files = bubbles.files
    val stickers = bubbles.stickers
    val meta = remember(message.content, message.msgType) {
        if (message.msgType == MsgType.Text || message.msgType == MsgType.Sticker) null else MessagePreview.decodeFileMeta(message.content)
    }
    val sticker = remember(message.content, message.msgType) {
        if (message.msgType == MsgType.Sticker) StickerRef.decode(message.content) else null
    }
    // 图片 / 贴纸不画气泡底色
    if (stickers != null && sticker != null) {
        StickerView(sticker, stickers, 140.dp)
        return
    }
    if (files != null && meta != null && displayType(message.msgType, meta) == MsgType.Image) {
        ImageBubble(meta, files, bubbles.onPreview)
        return
    }
    Surface(
        color = if (outgoing) colors.outgoingBubble else colors.incomingBubble,
        contentColor = if (outgoing) colors.outgoingText else colors.incomingText,
        shape = if (outgoing) GoImDimens.outgoingBubbleShape else GoImDimens.incomingBubbleShape,
        modifier = Modifier.widthIn(max = maxBubbleWidth),
    ) {
        val type = meta?.let { displayType(message.msgType, it) }
        when {
            files != null && meta != null && type == MsgType.Voice ->
                VoiceBubble(message, meta, files, bubbles.player, bubbles.onError)
            files != null && meta != null && (type == MsgType.Video || type == MsgType.File) ->
                FileCardBubble(meta, files, isVideo = type == MsgType.Video, maxWidth = maxBubbleWidth, onOpen = bubbles.onOpenFile, onError = bubbles.onError)
            else -> {
                val isText = message.msgType == MsgType.Text
                val text = @Composable {
                    Text(
                        if (isText) message.content else MessagePreview.listPreview(message),
                        fontStyle = if (isText) FontStyle.Normal else FontStyle.Italic,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = GoImDimens.bubblePaddingHorizontal, vertical = GoImDimens.bubblePaddingVertical),
                    )
                }
                if (bubbles.selectableText) SelectionContainer { text() } else text()
            }
        }
    }
}

/** msg_type=文件 但 MIME 是图片 / 视频时按对应类型展示；音频文件保持文件卡片，语音只认 msg_type=语音。 */
private fun displayType(type: MsgType, meta: FileMeta): MsgType {
    if (type != MsgType.File) return type
    val mime = meta.mime.lowercase()
    return when {
        mime.startsWith("image/") -> MsgType.Image
        mime.startsWith("video/") -> MsgType.Video
        else -> MsgType.File
    }
}
