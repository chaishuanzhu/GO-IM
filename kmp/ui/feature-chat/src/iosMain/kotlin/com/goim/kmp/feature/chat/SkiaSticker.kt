package com.goim.kmp.feature.chat

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import kotlin.math.min
import kotlin.math.roundToInt

/** Coil 在 Skia 平台只解第一帧：用 Skia `Codec` 逐帧解码到同一块位图，按帧时长重绘。 */
@Composable
internal fun SkiaAnimatedImage(bytes: ByteArray, modifier: Modifier) {
    val codec = remember(bytes) { runCatching { Codec.makeFromData(Data.makeFromBytes(bytes)) }.getOrNull() } ?: return
    val bitmap = remember(codec) { Bitmap().apply { allocPixels(codec.imageInfo) } }
    val image = remember(bitmap) { bitmap.asComposeImageBitmap() }
    var tick by remember(codec) { mutableIntStateOf(0) }

    LaunchedEffect(codec) {
        val count = codec.frameCount
        val durations = codec.framesInfo.map { it.duration }
        var frame = 0
        while (true) {
            runCatching { codec.readPixels(bitmap, frame) }
            bitmap.notifyPixelsChanged()
            tick++
            if (count <= 1) break
            delay(durations.getOrElse(frame) { 100 }.coerceAtLeast(20).toLong())
            frame = (frame + 1) % count
        }
    }

    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION")
        tick
        val scale = min(size.width / codec.width, size.height / codec.height)
        val w = (codec.width * scale).roundToInt()
        val h = (codec.height * scale).roundToInt()
        drawImage(
            image,
            srcSize = IntSize(codec.width, codec.height),
            dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
            dstSize = IntSize(w, h),
        )
    }
}
