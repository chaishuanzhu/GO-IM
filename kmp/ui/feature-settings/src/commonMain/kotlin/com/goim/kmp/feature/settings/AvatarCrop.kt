package com.goim.kmp.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Size
import com.goim.kmp.designsystem.GoImMediaColors
import com.goim.kmp.designsystem.GoImSize
import com.goim.kmp.designsystem.GoImSpacing
import com.goim.kmp.platform.EncodedImage
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

/** 裁剪结果：原图像素坐标下的正方形。 */
data class CropSquare(val left: Int, val top: Int, val size: Int)

/**
 * 圆形取景框的平移 / 缩放（像素）。缩放 1 时图片短边恰好填满取景框；
 * [offset] 是图片中心相对取景框中心的位移，始终保证取景框被图片盖满。
 */
class AvatarCropState(private val imageWidth: Int, private val imageHeight: Int, private val frame: Float) {
    private val baseScale = frame / min(imageWidth, imageHeight)

    var zoom by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    val scale: Float get() = baseScale * zoom

    /** [focus] 为缩放中心，相对取景框中心。 */
    fun transform(pan: Offset, zoomBy: Float, focus: Offset = Offset.Zero) {
        val next = (zoom * zoomBy).coerceIn(1f, MAX_ZOOM)
        val k = next / zoom
        zoom = next
        offset = clamp((offset - focus) * k + focus + pan)
    }

    fun result(): CropSquare {
        val side = frame / scale
        val left = imageWidth / 2f - offset.x / scale - side / 2
        val top = imageHeight / 2f - offset.y / scale - side / 2
        val size = side.roundToInt().coerceIn(1, min(imageWidth, imageHeight))
        return CropSquare(
            left.roundToInt().coerceIn(0, imageWidth - size),
            top.roundToInt().coerceIn(0, imageHeight - size),
            size,
        )
    }

    private fun clamp(o: Offset): Offset {
        val maxX = (imageWidth * scale - frame) / 2
        val maxY = (imageHeight * scale - frame) / 2
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    companion object {
        const val MAX_ZOOM = 5f
    }
}

/** 全屏头像裁剪：拖动 / 双指（桌面滚轮）缩放，圆形取景框内即头像。 */
@Composable
fun AvatarCropDialog(image: EncodedImage, onCancel: () -> Unit, onConfirm: (CropSquare) -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = GoImMediaColors.background, contentColor = GoImMediaColors.content) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                var state by remember { mutableStateOf<AvatarCropState?>(null) }
                val buttonColors = ButtonDefaults.textButtonColors(contentColor = GoImMediaColors.content)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = GoImSpacing.s2),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCancel, colors = buttonColors) { Text("取消") }
                    Text("移动和缩放", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { state?.let { onConfirm(it.result()) } }, enabled = state != null, colors = buttonColors) {
                        Text("完成")
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
                    val density = LocalDensity.current
                    val framePx = with(density) { (min(maxWidth.toPx(), maxHeight.toPx()) - 2 * GoImSpacing.s6.toPx()).coerceAtLeast(1f) }
                    val crop = remember(image, framePx) { AvatarCropState(image.width, image.height, framePx) }
                    SideEffect { state = crop }
                    CropArea(image, crop, framePx)
                }
                Text(
                    "拖动调整位置，双指或滚轮缩放",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(GoImSpacing.s4),
                )
            }
        }
    }
}

@Composable
private fun CropArea(image: EncodedImage, crop: AvatarCropState, framePx: Float) {
    val density = LocalDensity.current
    val request = ImageRequest.Builder(LocalPlatformContext.current).data(image.bytes).size(Size.ORIGINAL).build()
    val base = framePx / min(image.width, image.height)
    val gestures = Modifier
        .fillMaxSize()
        .pointerInput(crop) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                crop.transform(pan, zoom, centroid - Offset(size.width / 2f, size.height / 2f))
            }
        }
        .pointerInput(crop) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Scroll) continue
                    val change = event.changes.firstOrNull() ?: continue
                    val focus = change.position - Offset(size.width / 2f, size.height / 2f)
                    crop.transform(Offset.Zero, exp(-change.scrollDelta.y * WHEEL_ZOOM_STEP), focus)
                    change.consume()
                }
            }
        }
    BoxWithConstraints(gestures, contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .requiredSize(with(density) { (image.width * base).toDp() }, with(density) { (image.height * base).toDp() })
                .graphicsLayer {
                    scaleX = crop.zoom
                    scaleY = crop.zoom
                    translationX = crop.offset.x
                    translationY = crop.offset.y
                },
        )
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            val radius = framePx / 2
            drawRect(GoImMediaColors.scrim)
            drawCircle(GoImMediaColors.background, radius, blendMode = BlendMode.Clear)
            drawCircle(GoImMediaColors.content, radius, style = Stroke(GoImSize.strokeThin.toPx() / 2))
        }
    }
}

private const val WHEEL_ZOOM_STEP = 0.1f
