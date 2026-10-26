package com.goim.kmp.feature.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.goim.kmp.domain.FileRepository
import com.goim.kmp.domain.StickerRepository
import com.goim.kmp.model.Message
import com.goim.kmp.platform.createVoicePlayer

/** [ReadOnlyBubbles] 提供的气泡渲染器，只能在其 content 内使用。 */
class ReadOnlyBubbleScope internal constructor(internal val env: BubbleEnv)

/**
 * 在聊天页之外只读展示消息（如管理后台）时，用与聊天页相同的气泡：图片、贴纸、语音、文件卡片，
 * 点开图片 / 文件的预览也一致。附件按 [files] / [stickers] 加载。
 */
@Composable
fun ReadOnlyBubbles(
    files: FileRepository,
    stickers: StickerRepository,
    onError: (String) -> Unit,
    content: @Composable ReadOnlyBubbleScope.() -> Unit,
) {
    val platformContext = rememberPlatformContext()
    val player = remember { createVoicePlayer(platformContext) }
    var preview by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var filePreview by remember { mutableStateOf<FilePreviewRequest?>(null) }
    val reportError by rememberUpdatedState(onError)
    DisposableEffect(player) { onDispose { player.stop() } }
    val scope = remember(files, stickers, player) {
        ReadOnlyBubbleScope(
            BubbleEnv(
                files,
                stickers,
                player,
                selfName = "",
                onPreview = { model, local -> preview = model to local },
                onOpenFile = { path, meta ->
                    val kind = if (inAppFilePreview) filePreviewKind(meta) else null
                    if (kind != null) filePreview = FilePreviewRequest(path, meta, kind) else openWithSystem(path, meta.name)
                },
                onError = { reportError(it) },
                selectableText = false,
            ),
        )
    }
    scope.content()
    preview?.let { (model, local) -> ImagePreviewDialog(model, local, onDismiss = { preview = null }) }
    filePreview?.let { FilePreviewDialog(it, onDismiss = { filePreview = null }) }
}

/**
 * 与聊天页一致的一条消息：头像 + 昵称时间 + 气泡。[Message.isOutgoing] 决定靠右；
 * [avatarUrl] 为空时从资料缓存读取头像。长按气泡触发 [onLongPress]。
 */
@Composable
fun ReadOnlyBubbleScope.MessageBubble(
    message: Message,
    senderName: String,
    avatarUrl: String?,
    maxBubbleWidth: Dp,
    onLongPress: (() -> Unit)? = null,
) {
    BubbleRow(message, senderName, avatarUrl, maxBubbleWidth, env, onLongPress = onLongPress)
}

/**
 * 长按（超时前未抬起、未移动超过 touch slop）触发 [action]，之后吞掉本次手势，子元素不会再收到点击。
 * 在 Initial 阶段观察，所以图片、文件卡片等自身可点的气泡上也能长按。
 */
internal fun Modifier.onLongPress(action: () -> Unit): Modifier = semantics {
    onLongClick { action(); true }
}.pointerInput(action) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var ended = false
            while (!ended) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                ended = change == null || !change.pressed ||
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop
            }
        }
        if (released == null) {
            action()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}
