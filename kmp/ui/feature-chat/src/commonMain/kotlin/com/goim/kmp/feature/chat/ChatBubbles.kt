package com.goim.kmp.feature.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.goim.kmp.common.userMessage
import com.goim.kmp.designsystem.GoImFormat
import com.goim.kmp.designsystem.GoImIcons
import com.goim.kmp.designsystem.GoImSpacing
import com.goim.kmp.designsystem.GoImSize
import com.goim.kmp.designsystem.GoImDimens
import com.goim.kmp.designsystem.GoImMediaColors
import com.goim.kmp.domain.FileDownloadEvent
import com.goim.kmp.domain.FileRepository
import com.goim.kmp.domain.StickerRepository
import com.goim.kmp.model.FileMeta
import com.goim.kmp.model.Message
import com.goim.kmp.model.StickerRef
import com.goim.kmp.platform.VoicePlayer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.min

/** 图片气泡尺寸（同 iOS）：宽高不超过 220×260，短边不小于 120，缺尺寸时 180×180。 */
internal fun imageBubbleSize(width: Int?, height: Int?): Pair<Dp, Dp> {
    if (width == null || height == null || width <= 0 || height <= 0) return 180.dp to 180.dp
    val maxW = 220f
    val maxH = 260f
    val minSide = 120f
    val scale = min(maxW / width, maxH / height)
    var w = width * scale
    var h = height * scale
    val shortSide = min(w, h)
    if (shortSide < minSide) {
        val up = minSide / shortSide
        w = min(maxW, w * up)
        h = min(maxH, h * up)
    }
    return w.dp to h.dp
}

@Composable
private fun uploadProgress(files: FileRepository, fileId: String): Float? {
    val flow = remember(files, fileId) { files.uploadProgress.map { it[fileId] } }
    val progress by flow.collectAsState(null)
    return progress
}

@Composable
internal fun ImageBubble(meta: FileMeta, files: FileRepository, onOpen: (model: String, localPath: String?) -> Unit) {
    val (w, h) = imageBubbleSize(meta.width ?: meta.thumbWidth, meta.height ?: meta.thumbHeight)
    val local = remember(meta.fileId) { files.localPath(meta.fileId) }
    val display = local ?: files.remoteUrl(meta.fileId, thumb = meta.thumbWidth != null)
    val full = local ?: files.remoteUrl(meta.fileId, thumb = false)
    val progress = uploadProgress(files, meta.fileId)
    Box(
        Modifier
            .size(w, h)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = full != null, role = Role.Image, onClickLabel = "查看大图") { full?.let { onOpen(it, local) } },
        contentAlignment = Alignment.Center,
    ) {
        if (display != null) {
            AsyncImage(model = display, contentDescription = "图片", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (progress != null) ProgressOverlay(progress)
    }
}

@Composable
private fun ProgressOverlay(progress: Float) {
    Box(
        Modifier.size(GoImSize.touchTarget).background(GoImMediaColors.scrim, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(progress = { progress }, color = GoImMediaColors.content, strokeWidth = GoImSize.strokeThin, modifier = Modifier.size(32.dp))
    }
}

@Composable
internal fun VoiceBubble(message: Message, meta: FileMeta, files: FileRepository, player: VoicePlayer, onError: (String) -> Unit) {
    val playback by player.state.collectAsState()
    val playing = playback.key == message.id
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    val seconds = meta.duration ?: 1
    val width = (80 + seconds * 5).coerceIn(90, 200).dp
    Row(
        Modifier
            .width(width)
            .clickable(role = Role.Button, onClickLabel = "播放语音") {
                if (playing) {
                    player.stop()
                } else if (!loading) {
                    scope.launch {
                        loading = true
                        try {
                            player.play(message.id, files.ensureLocal(meta.fileId, meta.name))
                        } catch (e: Exception) {
                            onError(e.userMessage())
                        } finally {
                            loading = false
                        }
                    }
                }
            }
            .padding(horizontal = GoImDimens.bubblePaddingHorizontal, vertical = GoImDimens.bubblePaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GoImSpacing.s2),
    ) {
        when {
            loading -> CircularProgressIndicator(Modifier.size(GoImSize.iconSm), strokeWidth = GoImSize.strokeThin, color = LocalContentColor.current)
            playing -> Icon(GoImIcons.Stop, contentDescription = "停止", modifier = Modifier.size(GoImSize.iconSm))
            else -> Icon(Icons.Filled.PlayArrow, contentDescription = "播放", modifier = Modifier.size(GoImSize.iconSm))
        }
        VoiceWaveform(active = playing, level = if (playing) 1f else 0.6f)
        Spacer(Modifier.weight(1f))
        Text("语音 $seconds\"", style = MaterialTheme.typography.bodyMedium)
    }
}

private val WAVE_PATTERN = listOf(0.45f, 0.8f, 0.55f, 1f, 0.65f, 0.4f)

/** 6 根柱的波形；播放 / 录音中跳动。 */
@Composable
internal fun VoiceWaveform(active: Boolean, level: Float, barHeight: Dp = 16.dp, color: Color = LocalContentColor.current) {
    val transition = rememberInfiniteTransition()
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse))
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        WAVE_PATTERN.forEachIndexed { i, base ->
            val wobble = if (active) 0.35f + 0.65f * (if (i % 2 == 0) phase else 1f - phase) else 1f
            val fraction = (base * wobble * level.coerceIn(0.2f, 1f)).coerceIn(0.15f, 1f)
            Box(Modifier.width(3.dp).height(barHeight * fraction).background(color, CircleShape))
        }
    }
}

@Composable
internal fun FileCardBubble(
    meta: FileMeta,
    files: FileRepository,
    isVideo: Boolean,
    maxWidth: Dp,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var download by remember(meta.fileId) { mutableStateOf<Float?>(null) }
    val upload = uploadProgress(files, meta.fileId)
    val title = if (isVideo) {
        listOfNotNull("视频", meta.duration?.let { "$it\"" }, meta.name.takeIf { it.isNotBlank() }).joinToString(" · ")
    } else {
        meta.name.ifBlank { "文件" }
    }
    val subtitle = when {
        upload != null -> "上传中 ${(upload * 100).toInt()}%"
        download != null -> "下载中 ${((download ?: 0f) * 100).toInt()}%"
        else -> GoImFormat.fileSize(meta.size)
    }
    Row(
        Modifier
            .widthIn(min = 180.dp, max = maxWidth)
            .clickable(enabled = download == null, role = Role.Button, onClickLabel = "打开") {
                scope.launch {
                    files.observeDownload(meta.fileId, meta.name).collect { event ->
                        when (event) {
                            is FileDownloadEvent.Progress -> download = event.fraction
                            is FileDownloadEvent.Completed -> {
                                download = null
                                openWithSystem(event.path, meta.name)
                            }
                            is FileDownloadEvent.Failed -> {
                                download = null
                                onError(event.message)
                            }
                        }
                    }
                }
            }
            .padding(horizontal = GoImDimens.bubblePaddingHorizontal, vertical = GoImDimens.bubblePaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GoImSpacing.s3),
    ) {
        Box(
            Modifier.size(GoImSize.iconLg).background(LocalContentColor.current.copy(alpha = 0.12f), MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            val progress = upload ?: download
            if (progress != null) {
                CircularProgressIndicator(progress = { progress }, strokeWidth = GoImSize.strokeThin, modifier = Modifier.size(GoImSize.iconMd), color = LocalContentColor.current)
            } else {
                Icon(if (isVideo) GoImIcons.Videocam else GoImIcons.File, contentDescription = null)
            }
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(alpha = 0.7f))
        }
    }
}

@Composable
internal fun StickerView(ref: StickerRef, stickers: StickerRepository, size: Dp, modifier: Modifier = Modifier) = key(ref.key) {
    // produceState 的值不随 key 重置，换贴纸时须整体重建，否则会残留上一张
    val bytes by produceState<ByteArray?>(null) { value = runCatching { stickers.imageBytes(ref) }.getOrNull() }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        val data = bytes
        if (data != null) {
            AnimatedSticker(data, Modifier.fillMaxSize())
        } else {
            Box(Modifier.size(size * 0.6f).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium))
        }
    }
}

/** 全屏图片预览：双指 / 滚轮缩放、拖动，双击切换 1x / 2.5x，单击关闭。 */
@Composable
internal fun ImagePreviewDialog(model: String, localPath: String?, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(GoImMediaColors.background)) {
            AsyncImage(
                model = model,
                contentDescription = "图片预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale > 1f) offset + pan else Offset.Zero
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            },
                            onTap = { onDismiss() },
                        )
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Scroll) {
                                    val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                    scale = (scale * if (dy < 0) 1.1f else 0.9f).coerceIn(1f, 5f)
                                    if (scale == 1f) offset = Offset.Zero
                                }
                            }
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
            Row(Modifier.align(Alignment.TopEnd).padding(GoImSpacing.s3)) {
                if (localPath != null) {
                    IconButton(onClick = { openWithSystem(localPath, "image") }) {
                        Icon(GoImIcons.Download, contentDescription = "用系统打开", tint = GoImMediaColors.content)
                    }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "关闭", tint = GoImMediaColors.content) }
            }
        }
    }
}
