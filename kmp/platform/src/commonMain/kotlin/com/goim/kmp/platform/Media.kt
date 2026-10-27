package com.goim.kmp.platform

import com.goim.kmp.common.PlatformContext
import kotlinx.coroutines.flow.StateFlow

class EncodedImage(val bytes: ByteArray, val width: Int, val height: Int)

/** 平台图片编解码：按 EXIF 方向摆正、等比缩放到 [maxDimension] 内、输出 JPEG（透明区域铺白）。 */
expect object ImageCodec {
    /** 摆正后的像素尺寸；不是可解码图片时返回 null。 */
    fun dimensions(bytes: ByteArray): Pair<Int, Int>?

    fun encodeJpeg(bytes: ByteArray, maxDimension: Int, quality: Float): EncodedImage?

    /** 从已摆正的图片（[encodeJpeg] 的输出）裁出以 [left], [top] 为左上角、边长 [size] 的正方形，缩放到 [outSize] 输出 JPEG。 */
    fun cropSquareJpeg(bytes: ByteArray, left: Int, top: Int, size: Int, outSize: Int, quality: Float): EncodedImage?
}

/** 与 iOS `ImageCompressor` 相同的压缩阶梯；结果总是 JPEG。 */
object ImageCompressor {
    const val TARGET_BYTES = 900_000
    const val ORIGINAL_SOFT_LIMIT = 8_000_000
    const val HARD_LIMIT = 10 * 1024 * 1024

    /** 原图也设上限，避免超大图解码撑爆内存（Android 位图）。 */
    private const val ORIGINAL_MAX_DIMENSION = 8192

    fun compress(bytes: ByteArray, original: Boolean): EncodedImage? {
        if (original) {
            val full = ImageCodec.encodeJpeg(bytes, ORIGINAL_MAX_DIMENSION, 0.92f) ?: return null
            if (full.bytes.size <= ORIGINAL_SOFT_LIMIT) return full
        }
        ladder(bytes, 4096, 0.72f, 0.28f, 0.08f, TARGET_BYTES)?.let { return it }
        ladder(bytes, 1600, 0.55f, 0.25f, 0.08f, TARGET_BYTES)?.let { return it }
        ImageCodec.encodeJpeg(bytes, 1280, 0.45f)?.takeIf { it.bytes.size <= HARD_LIMIT }?.let { return it }
        return ladder(bytes, 1024, 0.4f, 0.05f, 0.05f, HARD_LIMIT)
            ?: ImageCodec.encodeJpeg(bytes, 1024, 0.05f)
    }

    private fun ladder(bytes: ByteArray, max: Int, start: Float, floor: Float, step: Float, limit: Int): EncodedImage? {
        var q = start
        while (q >= floor - 0.001f) {
            val out = ImageCodec.encodeJpeg(bytes, max, q) ?: return null
            if (out.bytes.size <= limit) return out
            q -= step
        }
        return null
    }
}

class RecordedAudio(val path: String, val durationMillis: Long, val mime: String = "audio/mp4")

/** 语音录制：Android / iOS 为 AAC m4a、16 kHz、单声道、32 kbps（同 iOS）；桌面为 16 kHz 单声道 PCM WAV。 */
interface VoiceRecorder {
    val isSupported: Boolean

    /** 输出文件扩展名（不含点）。 */
    val fileExtension: String get() = "m4a"

    /** 调用前需已获得麦克风权限。 */
    fun start(outputPath: String): Boolean

    /** 0..1 音量，用于波形。 */
    fun level(): Float

    fun stop(): RecordedAudio?

    fun cancel()
}

expect fun createVoiceRecorder(context: PlatformContext): VoiceRecorder

data class PlaybackState(val key: String? = null, val progress: Float = 0f)

/** 单实例语音播放：播放新的会停掉旧的；桌面端 WAV 内置播放，其他格式交给系统播放器（状态不追踪）。 */
interface VoicePlayer {
    val state: StateFlow<PlaybackState>

    fun play(key: String, path: String)

    fun stop()
}

expect fun createVoicePlayer(context: PlatformContext): VoicePlayer

/** 音视频时长（秒，四舍五入，至少 1）；无法读取时返回 null。 */
expect fun mediaDurationSeconds(path: String): Int?
