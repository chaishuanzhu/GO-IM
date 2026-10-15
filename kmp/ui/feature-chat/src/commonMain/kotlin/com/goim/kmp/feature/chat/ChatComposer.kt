package com.goim.kmp.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import com.goim.kmp.designsystem.UserAvatar
import com.goim.kmp.designsystem.rememberDisplayName
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.goim.kmp.common.userMessage
import com.goim.kmp.designsystem.GoImIcons
import com.goim.kmp.designsystem.GoImDimens
import com.goim.kmp.designsystem.GoImSpacing
import com.goim.kmp.designsystem.GoImSize
import com.goim.kmp.designsystem.GoImElevation
import com.goim.kmp.platform.VoiceRecorder
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class ComposerPanel { NONE, EMOJI, VOICE, MORE }

/** iOS `EmojiPanelView` 的 32 个常用表情。 */
internal val EMOJIS = listOf(
    "😀", "😂", "🥰", "😍", "🤔", "😎", "😭", "😡",
    "👍", "👎", "👏", "🙏", "🔥", "✨", "🎉", "❤️",
    "🥰", "😊", "🤗", "😴", "🤝", "💪", "🌟", "💯",
    "😅", "🤣", "😘", "😜", "🥺", "😱", "🙄", "😇",
)


@Composable
internal fun ChatComposer(
    /** 每次自增都收起当前面板（点消息列表空白处）。 */
    collapseRequests: Int,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    media: ChatMediaActions?,
    recorder: VoiceRecorder,
    isGroup: Boolean,
    onAppend: (String) -> Unit,
    onMention: (String) -> Unit,
    onError: (String) -> Unit,
) {
    var panel by remember { mutableStateOf(ComposerPanel.NONE) }
    var pickedImages by remember { mutableStateOf<List<PickedFile>?>(null) }
    var mentionCandidates by remember { mutableStateOf<List<String>?>(null) }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(collapseRequests) { if (collapseRequests > 0) panel = ComposerPanel.NONE }

    fun toggle(target: ComposerPanel) {
        focus.clearFocus()
        panel = if (panel == target) ComposerPanel.NONE else target
    }

    fun openMentions() {
        val actions = media ?: return
        scope.launch { mentionCandidates = actions.mentionCandidates() }
    }

    suspend fun read(file: PlatformFile): PickedFile? = media?.read(file)

    val imagePicker = rememberFilePickerLauncher(
        type = FileKitType.Image,
        mode = FileKitMode.Multiple(maxItems = ChatMediaActions.MAX_IMAGES),
    ) { files ->
        if (!files.isNullOrEmpty()) scope.launch { pickedImages = files.take(ChatMediaActions.MAX_IMAGES).mapNotNull { read(it) } }
    }
    val videoPicker = rememberFilePickerLauncher(type = FileKitType.Video) { file ->
        if (file != null && media != null) scope.launch { read(file)?.let(media::sendVideo) }
    }
    val filePicker = rememberFilePickerLauncher(type = FileKitType.File()) { file ->
        if (file != null && media != null) scope.launch { read(file)?.let(media::sendDocument) }
    }
    val camera = rememberCameraCapture { file -> if (file != null) media?.sendCameraPhoto(file) }

    fun onInput(value: String) {
        // 群聊中输入 @ 弹出成员选择
        if (isGroup && value.length == draft.length + 1 && value.endsWith("@")) openMentions()
        onDraftChange(value)
    }

    if (usesDesktopComposer) {
        DesktopComposer(
            draft = draft,
            onInput = ::onInput,
            onSend = onSend,
            media = media,
            recorder = recorder,
            isGroup = isGroup,
            panel = panel,
            onPanelChange = { panel = it },
            onMention = ::openMentions,
            onPickImage = { imagePicker.launch() },
            onPickVideo = { videoPicker.launch() },
            onPickFile = { filePicker.launch() },
            onAppend = onAppend,
            onError = onError,
        )
    } else Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = GoImSpacing.s3, end = GoImSpacing.s2, top = GoImSpacing.s2, bottom = if (media == null) GoImSpacing.s2 else 0.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(GoImSpacing.s2),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = ::onInput,
                maxLines = 5,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { if (it.isFocused) panel = ComposerPanel.NONE }
                    .enterToSend(onSend),
                decorationBox = { input ->
                    Box(
                        Modifier
                            .heightIn(min = GoImDimens.inputHeight)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.extraLarge)
                            .padding(horizontal = GoImSpacing.s4, vertical = GoImSpacing.s2),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (draft.isEmpty()) {
                            Text("发消息", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        input()
                    }
                },
            )
            FilledIconButton(
                onClick = onSend,
                enabled = draft.isNotBlank(),
                modifier = Modifier.size(GoImDimens.inputHeight),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
        }
        if (media != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = GoImSpacing.s2, vertical = GoImSpacing.s1), horizontalArrangement = Arrangement.SpaceAround) {
                ToolButton(if (panel == ComposerPanel.VOICE) GoImIcons.Keyboard else GoImIcons.Mic, "语音", panel == ComposerPanel.VOICE) {
                    toggle(ComposerPanel.VOICE)
                }
                ToolButton(GoImIcons.Emoji, "表情", panel == ComposerPanel.EMOJI) {
                    if (panel != ComposerPanel.EMOJI) media.loadStickers()
                    toggle(ComposerPanel.EMOJI)
                }
                IconButton(onClick = ::openMentions, modifier = Modifier.semantics { contentDescription = "提及成员" }) {
                    Text("@", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ToolButton(GoImIcons.Image, "图片", false) { imagePicker.launch() }
                ToolButton(Icons.Filled.Add, "更多", panel == ComposerPanel.MORE) { toggle(ComposerPanel.MORE) }
            }
            when (panel) {
                ComposerPanel.EMOJI -> EmojiStickerPanel(media, onEmoji = onAppend)
                ComposerPanel.VOICE -> VoicePanel(media, recorder, onError)
                ComposerPanel.MORE -> MorePanel(
                    onImage = { imagePicker.launch() },
                    onCamera = camera,
                    onVideo = { videoPicker.launch() },
                    onFile = { filePicker.launch() },
                )
                ComposerPanel.NONE -> Unit
            }
        }
    }
    }

    pickedImages?.let { images ->
        if (images.isEmpty()) {
            pickedImages = null
        } else {
            ImageConfirmDialog(
                images = images,
                onDismiss = { pickedImages = null },
                onSend = { original ->
                    pickedImages = null
                    media?.sendImages(images, original)
                },
            )
        }
    }

    mentionCandidates?.let { candidates ->
        MentionDialog(
            candidates = candidates,
            onDismiss = { mentionCandidates = null },
            onPick = { uid ->
                mentionCandidates = null
                onMention(uid)
            },
        )
    }
}

/** 硬件键盘：Enter 发送，Shift+Enter 换行。 */
private fun Modifier.enterToSend(onSend: () -> Unit) = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
        onSend()
        true
    } else {
        false
    }
}

/** 桌面社交软件风格：顶部工具栏、无边框多行编辑区、右下角发送按钮；表情 / 语音以浮层弹出。 */
@Composable
private fun DesktopComposer(
    draft: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    media: ChatMediaActions?,
    recorder: VoiceRecorder,
    isGroup: Boolean,
    panel: ComposerPanel,
    onPanelChange: (ComposerPanel) -> Unit,
    onMention: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickFile: () -> Unit,
    onAppend: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val editor = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { editor.requestFocus() } }
    // 点工具按钮时浮层会先因「点击外部」关闭，紧接着的 toggle 不应再把它打开
    var lastDismiss by remember { mutableStateOf(TimeSource.Monotonic.markNow() - 1.seconds) }

    fun toggle(target: ComposerPanel) {
        if (panel == ComposerPanel.NONE && lastDismiss.elapsedNow() < 300.milliseconds) return
        onPanelChange(if (panel == target) ComposerPanel.NONE else target)
    }

    fun dismissPanel() {
        lastDismiss = TimeSource.Monotonic.markNow()
        onPanelChange(ComposerPanel.NONE)
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (media != null) {
                Box {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = GoImSpacing.s2, vertical = GoImSpacing.s1),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ToolButton(GoImIcons.Emoji, "表情", panel == ComposerPanel.EMOJI) {
                            if (panel != ComposerPanel.EMOJI) media.loadStickers()
                            toggle(ComposerPanel.EMOJI)
                        }
                        if (isGroup) {
                            IconButton(onClick = onMention, modifier = Modifier.semantics { contentDescription = "提及成员" }) {
                                Text("@", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        ToolButton(GoImIcons.Image, "图片", false, onPickImage)
                        ToolButton(GoImIcons.Videocam, "视频", false, onPickVideo)
                        ToolButton(GoImIcons.File, "文件", false, onPickFile)
                        ToolButton(GoImIcons.Mic, "语音", panel == ComposerPanel.VOICE) { toggle(ComposerPanel.VOICE) }
                    }
                    if (panel == ComposerPanel.EMOJI || panel == ComposerPanel.VOICE) {
                        Popup(
                            popupPositionProvider = AboveAnchor,
                            onDismissRequest = ::dismissPanel,
                            properties = PopupProperties(focusable = true),
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shadowElevation = GoImElevation.level4,
                                modifier = Modifier.width(if (panel == ComposerPanel.EMOJI) 400.dp else 300.dp),
                            ) {
                                if (panel == ComposerPanel.EMOJI) {
                                    EmojiStickerPanel(media, onEmoji = onAppend)
                                } else {
                                    VoicePanel(media, recorder, onError)
                                }
                            }
                        }
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp, max = 200.dp)
                    .padding(horizontal = GoImSpacing.s4, vertical = GoImSpacing.s1),
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = onInput,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().focusRequester(editor).enterToSend(onSend),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) {
                                Text(
                                    "输入消息，可拖入文件发送",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(start = GoImSpacing.s4, end = GoImSpacing.s4, bottom = GoImSpacing.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Enter 发送 · Shift+Enter 换行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onSend, enabled = draft.isNotBlank()) { Text("发送") }
            }
        }
    }
}

/** 浮层贴在锚点（工具栏）上方、左对齐。 */
private object AboveAnchor : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.left + 8).coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val y = (anchorBounds.top - popupContentSize.height - 8).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    IconToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        colors = IconButtonDefaults.iconToggleButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Icon(icon, contentDescription = label)
    }
}

private val PANEL_HEIGHT = 260.dp

@Composable
private fun EmojiStickerPanel(media: ChatMediaActions, onEmoji: (String) -> Unit) {
    val packs by media.stickerPacks.collectAsState()
    val recent by media.recentStickers.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("表情", "最近") + packs.map { it.name }
    Column(Modifier.fillMaxWidth().height(PANEL_HEIGHT)) {
        PrimaryScrollableTabRow(selectedTabIndex = tab.coerceAtMost(tabs.lastIndex), edgePadding = GoImSpacing.s2) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title, maxLines = 1) })
            }
        }
        when (tab) {
            0 -> LazyVerticalGrid(
                columns = GridCells.Adaptive(GoImSize.touchTarget),
                contentPadding = PaddingValues(GoImSpacing.s2),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(EMOJIS.size) { i ->
                    Box(
                        Modifier.size(GoImSize.touchTarget).clip(MaterialTheme.shapes.small).clickable { onEmoji(EMOJIS[i]) },
                        contentAlignment = Alignment.Center,
                    ) { Text(EMOJIS[i], style = MaterialTheme.typography.headlineSmall) }
                }
            }
            1 -> if (recent.isEmpty()) {
                PanelHint("还没有最近使用的贴纸")
            } else {
                StickerGrid(recent.size) { i ->
                    StickerView(recent[i], media.stickers, 64.dp, Modifier.fillMaxSize().clickable { media.sendSticker(recent[i]) })
                }
            }
            else -> {
                val pack = packs.getOrNull(tab - 2)
                if (pack == null) {
                    PanelHint("加载中…")
                } else key(pack.packId) {
                    // 各贴纸包共用同一调用点：按 packId 重建，否则 produceState 会沿用上一个包的内容
                    val items by produceState(pack.stickers) {
                        if (value.isEmpty()) value = media.stickerItems(pack.packId)
                    }
                    if (items.isEmpty()) {
                        PanelHint("贴纸加载中…")
                    } else {
                        StickerGrid(items.size) { i ->
                            val item = items[i]
                            StickerView(item.asRef(), media.stickers, 64.dp, Modifier.fillMaxSize().clickable { media.sendSticker(item) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StickerGrid(count: Int, cell: @Composable (Int) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(72.dp),
        contentPadding = PaddingValues(GoImSpacing.s2),
        horizontalArrangement = Arrangement.spacedBy(GoImSpacing.s1),
        verticalArrangement = Arrangement.spacedBy(GoImSpacing.s1),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(count) { i -> Box(Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) { cell(i) } }
    }
}

@Composable
private fun PanelHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 按住说话、松开发送、滑出按钮区域取消（同 iOS `VoiceRecordPanel`）。 */
@Composable
private fun VoicePanel(media: ChatMediaActions, recorder: VoiceRecorder, onError: (String) -> Unit) {
    if (!recorder.isSupported) {
        Box(Modifier.fillMaxWidth().height(PANEL_HEIGHT)) { PanelHint("未检测到可用的麦克风") }
        return
    }
    val requestPermission = rememberMicrophonePermission()
    val scope = rememberCoroutineScope()
    var permitted by remember { mutableStateOf<Boolean?>(null) }
    var recording by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) { permitted = requestPermission() }
    LaunchedEffect(recording) {
        while (recording) {
            level = recorder.level()
            delay(80)
        }
    }
    DisposableEffect(recorder) { onDispose { recorder.cancel() } }

    fun beginRecording(): Boolean {
        if (!recorder.start(media.voiceOutputPath(recorder.fileExtension))) {
            onError("无法开始录音")
            return false
        }
        recording = true
        cancelling = false
        return true
    }

    fun finishRecording(send: Boolean) {
        recording = false
        cancelling = false
        if (!send) {
            recorder.cancel()
            return
        }
        val audio = recorder.stop()
        if (audio == null) onError("录音太短，请按住再试") else media.sendVoice(audio)
    }

    val hint = when {
        permitted == null -> "准备中…"
        permitted == false -> "需要麦克风权限，点按重试"
        recording && cancelling -> "松开取消"
        recording -> "松开发送 · 滑出取消"
        else -> "按住说话"
    }
    Column(
        Modifier.fillMaxWidth().height(PANEL_HEIGHT),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            hint,
            color = if (cancelling) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Box(Modifier.height(36.dp), contentAlignment = Alignment.Center) {
            if (recording) VoiceWaveform(active = true, level = 0.3f + level * 0.7f, barHeight = 28.dp)
        }
        Surface(
            shape = CircleShape,
            color = when {
                cancelling -> MaterialTheme.colorScheme.error
                recording -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.primaryContainer
            },
            modifier = Modifier
                .size(112.dp)
                .pointerInput(permitted) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (permitted != true) {
                            scope.launch { permitted = requestPermission() }
                            return@awaitEachGesture
                        }
                        if (!beginRecording()) return@awaitEachGesture
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val p = change.position
                            val margin = GoImSpacing.s6.toPx()
                            cancelling = p.x < -margin || p.y < -margin || p.x > size.width + margin || p.y > size.height + margin
                            if (!change.pressed) break
                        }
                        finishRecording(send = !cancelling)
                    }
                }
                // 读屏 / 键盘无法「按住」：点按开始，再次点按发送
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = "语音消息"
                    stateDescription = if (recording) "正在录音" else "未录音"
                    onClick(label = if (recording) "停止并发送" else "开始录音") {
                        when {
                            permitted != true -> scope.launch { permitted = requestPermission() }
                            recording -> finishRecording(send = true)
                            else -> beginRecording()
                        }
                        true
                    }
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    GoImIcons.Mic,
                    contentDescription = "按住说话",
                    modifier = Modifier.size(GoImSize.iconLg),
                    tint = if (recording) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun MorePanel(onImage: () -> Unit, onCamera: (() -> Unit)?, onVideo: () -> Unit, onFile: () -> Unit) {
    val actions = buildList {
        add(Triple(GoImIcons.Image, "照片", onImage))
        onCamera?.let { add(Triple(GoImIcons.Camera, "拍照", it)) }
        add(Triple(GoImIcons.Videocam, "视频", onVideo))
        add(Triple(GoImIcons.File, "文件", onFile))
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(GoImSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(GoImSpacing.s4),
        modifier = Modifier.fillMaxWidth().height(PANEL_HEIGHT),
    ) {
        items(actions) { (icon, label, action) ->
            Column(
                Modifier.clip(MaterialTheme.shapes.large).clickable(role = Role.Button, onClick = action).padding(GoImSpacing.s1),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(56.dp).background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.large),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
                Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = GoImSpacing.s2))
            }
        }
    }
}

@Composable
private fun ImageConfirmDialog(images: List<PickedFile>, onDismiss: () -> Unit, onSend: (original: Boolean) -> Unit) {
    var original by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发送 ${images.size} 张图片") },
        text = {
            Column {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(GoImSpacing.s2)) {
                    items(images) { img ->
                        AsyncImage(
                            model = img.bytes,
                            contentDescription = img.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(96.dp).clip(MaterialTheme.shapes.small),
                        )
                    }
                }
                Row(
                    Modifier.padding(top = GoImSpacing.s2).clickable { original = !original },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = original, onCheckedChange = { original = it })
                    Text("原图")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSend(original) }) { Text("发送(${images.size})") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun MentionDialog(candidates: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要提醒的人") },
        text = {
            if (candidates.isEmpty()) {
                Text("没有可提醒的成员")
            } else {
                LazyColumn(Modifier.height(320.dp)) {
                    items(candidates) { uid ->
                        val name = rememberDisplayName(uid)
                        ListItem(
                            headlineContent = { Text(name, maxLines = 1) },
                            supportingContent = if (name != uid) ({ Text("ID：$uid", maxLines = 1) }) else null,
                            leadingContent = { UserAvatar(uid, name, size = GoImSize.avatarMd) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable { onPick(uid) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
