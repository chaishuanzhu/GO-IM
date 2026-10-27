package com.goim.kmp.platform

import com.goim.kmp.common.PlatformContext
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

actual object ImageCodec {
    actual fun dimensions(bytes: ByteArray): Pair<Int, Int>? = runCatching {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()

    actual fun encodeJpeg(bytes: ByteArray, maxDimension: Int, quality: Float): EncodedImage? = runCatching {
        val source = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val scale = minOf(1.0, maxDimension.toDouble() / max(source.width, source.height))
        val w = max(1, (source.width * scale).roundToInt())
        val h = max(1, (source.height * scale).roundToInt())
        val target = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        target.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            color = Color.WHITE
            fillRect(0, 0, w, h)
            drawImage(source, 0, 0, w, h, null)
            dispose()
        }
        writeJpeg(target, quality)
    }.getOrNull()

    actual fun cropSquareJpeg(bytes: ByteArray, left: Int, top: Int, size: Int, outSize: Int, quality: Float): EncodedImage? = runCatching {
        val source = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val side = size.coerceIn(1, minOf(source.width, source.height))
        val x = left.coerceIn(0, source.width - side)
        val y = top.coerceIn(0, source.height - side)
        val target = BufferedImage(outSize, outSize, BufferedImage.TYPE_INT_RGB)
        target.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            color = Color.WHITE
            fillRect(0, 0, outSize, outSize)
            drawImage(source, 0, 0, outSize, outSize, x, y, x + side, y + side, null)
            dispose()
        }
        writeJpeg(target, quality)
    }.getOrNull()

    private fun writeJpeg(target: BufferedImage, quality: Float): EncodedImage {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality.coerceIn(0.01f, 1f)
            }
            writer.write(null, IIOImage(target, null, null), param)
            writer.dispose()
        }
        return EncodedImage(out.toByteArray(), target.width, target.height)
    }
}

actual fun createVoiceRecorder(context: PlatformContext): VoiceRecorder = WavVoiceRecorder()

actual fun createVoicePlayer(context: PlatformContext): VoicePlayer = DesktopVoicePlayer()

actual fun mediaDurationSeconds(path: String): Int? = audioDurationSeconds(path)
