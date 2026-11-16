package com.goim.kmp.platform

import co.touchlab.kermit.Logger
import com.goim.kmp.common.PlatformContext
import com.goim.kmp.common.nowMillis
import kotlinx.coroutines.flow.StateFlow

class EncodedImage(val bytes: ByteArray, val width: Int, val height: Int)

/** 发图耗时诊断日志，logcat 过滤 tag `ImgSend`。 */
val imgSendLog = Logger.withTag("ImgSend")

inline fun <T> imgSendTimed(step: String, detail: String = "", block: () -> T): T {
    val t0 = nowMillis()
    return try {
        block().also { imgSendLog.i { "$step ok ${nowMillis() - t0}ms $detail" } }
    } catch (e: Throwable) {
        imgSendLog.e(e) { "$step fail ${nowMillis() - t0}ms $detail" }
        throw e
    }
}

/** 平台图片编解码：按 EXIF 方向摆正、等比缩放到 [maxDimension] 内、输出 JPEG（透明区域铺白）。 */
expect object ImageCodec {
    /** 摆正后的像素尺寸；不是可解码图片时返回 null。 */
    fun dimensions(bytes: ByteArray): Pair<Int, Int>?

    fun encodeJpeg(bytes: ByteArray, maxDimension: Int, quality: Float): EncodedImage?

    /** 从已摆正的图片（[encodeJpeg] 的输出）裁出以 [left], [top] 为左上角、边长 [size] 的正方形，缩放到 [outSize] 输出 JPEG。 */
    fun cropSquareJpeg(bytes: ByteArray, left: Int, top: Int, size: Int, outSize: Int, quality: Float): EncodedImage?
}

/**
 * 发送前图片压缩，结果总是 JPEG。
 *
 * 普通发送尽量一步到位：最长边 [DEFAULT_MAX_DIMENSION]、质量 [DEFAULT_QUALITY]
 * （接近主流 IM 的预览档）。绝大多数相册图一次编码即可落在 [TARGET_BYTES] 内；
 * 仅超大噪声图才再压一档。原图档最长边 [ORIGINAL_MAX_DIMENSION]、质量 0.92，
 * 超过 [ORIGINAL_SOFT_LIMIT] 时回落到普通档。
 */
object ImageCompressor {
    const val TARGET_BYTES = 900_000
    const val ORIGINAL_SOFT_LIMIT = 8_000_000
    const val HARD_LIMIT = 10 * 1024 * 1024

    const val DEFAULT_MAX_DIMENSION = 1600
    const val DEFAULT_QUALITY = 0.72f

    /** 原图也设上限，避免超大图解码撑爆内存（Android 位图）。 */
    private const val ORIGINAL_MAX_DIMENSION = 8192

    /** 一步压完仍超目标时的兜底：略缩小边长与质量，再编一次。 */
    private const val FALLBACK_MAX_DIMENSION = 1280
    private const val FALLBACK_QUALITY = 0.55f

    fun compress(bytes: ByteArray, original: Boolean): EncodedImage? = imgSendTimed(
        "compress",
        "in=${bytes.size} original=$original",
    ) {
        if (original) {
            val full = imgSendTimed("compress.encode.original", "max=$ORIGINAL_MAX_DIMENSION q=0.92") {
                ImageCodec.encodeJpeg(bytes, ORIGINAL_MAX_DIMENSION, 0.92f)
            } ?: return@imgSendTimed null
            imgSendLog.i { "compress.original out=${full.bytes.size} ${full.width}x${full.height}" }
            if (full.bytes.size <= ORIGINAL_SOFT_LIMIT) return@imgSendTimed full
            imgSendLog.i { "compress.original over soft limit, fall through to default" }
        }
        val once = imgSendTimed("compress.encode.once", "max=$DEFAULT_MAX_DIMENSION q=$DEFAULT_QUALITY") {
            ImageCodec.encodeJpeg(bytes, DEFAULT_MAX_DIMENSION, DEFAULT_QUALITY)
        }
        if (once == null) {
            imgSendLog.w { "compress.encode.once returned null" }
            return@imgSendTimed null
        }
        imgSendLog.i { "compress.once out=${once.bytes.size} ${once.width}x${once.height}" }
        if (once.bytes.size <= TARGET_BYTES) return@imgSendTimed once
        // 噪声很大的图偶发超 900KB：再编一次更小档；仍过大则保底 1024。
        val fallback = imgSendTimed("compress.encode.fallback", "max=$FALLBACK_MAX_DIMENSION q=$FALLBACK_QUALITY") {
            ImageCodec.encodeJpeg(bytes, FALLBACK_MAX_DIMENSION, FALLBACK_QUALITY)
        } ?: return@imgSendTimed once
        val best = if (fallback.bytes.size <= once.bytes.size) fallback else once
        imgSendLog.i { "compress.fallback out=${fallback.bytes.size} picked=${best.bytes.size}" }
        if (best.bytes.size <= HARD_LIMIT) return@imgSendTimed best
        imgSendTimed("compress.encode.hard", "max=1024 q=0.4") {
            ImageCodec.encodeJpeg(bytes, 1024, 0.4f)
        }
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
