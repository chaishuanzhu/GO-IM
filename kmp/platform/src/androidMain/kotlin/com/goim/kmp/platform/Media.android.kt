package com.goim.kmp.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

actual object ImageCodec {
    actual fun dimensions(bytes: ByteArray): Pair<Int, Int>? = runCatching {
        val heif = looksLikeHeif(bytes)
        imgSendLog.i { "dimensions sdk=${Build.VERSION.SDK_INT} heif=$heif in=${bytes.size} head=${hexHead(bytes)}" }
        // HEIF：不要走 BitmapFactory.inJustDecodeBounds（会打 HeifDecoderImpl getSize: not supported!）。
        if (Build.VERSION.SDK_INT >= 28 && heif) {
            return@runCatching imgSendTimed("dimensions.imageDecoder") { imageDecoderSize(bytes) }
        }
        val bounds = imgSendTimed("dimensions.bitmapFactoryBounds") { bitmapFactoryBounds(bytes) }
            ?: return@runCatching null
        if (rotation(bytes) % 180 != 0) bounds.second to bounds.first else bounds
    }.getOrNull()

    actual fun encodeJpeg(bytes: ByteArray, maxDimension: Int, quality: Float): EncodedImage? = runCatching {
        imgSendLog.i {
            "encodeJpeg start sdk=${Build.VERSION.SDK_INT} heif=${looksLikeHeif(bytes)} " +
                "in=${bytes.size} max=$maxDimension q=$quality head=${hexHead(bytes)}"
        }
        val decoded = decodeScaled(bytes, maxDimension)
        if (decoded == null) {
            imgSendLog.w { "encodeJpeg decodeScaled returned null" }
            return@runCatching null
        }
        try {
            imgSendTimed("encodeJpeg.toJpeg", "bitmap=${decoded.width}x${decoded.height}") {
                toJpeg(decoded, quality)
            }
        } finally {
            decoded.recycle()
        }
    }.getOrNull()

    actual fun cropSquareJpeg(bytes: ByteArray, left: Int, top: Int, size: Int, outSize: Int, quality: Float): EncodedImage? = runCatching {
        // 头像裁剪需要原图像素；上限与发送原图档一致，避免超大 HEIF 撑爆内存。
        val decoded = decodeScaled(bytes, 8192) ?: return@runCatching null
        try {
            val side = size.coerceIn(1, minOf(decoded.width, decoded.height))
            val x = left.coerceIn(0, decoded.width - side)
            val y = top.coerceIn(0, decoded.height - side)
            val out = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888)
            Canvas(out).apply {
                drawColor(Color.WHITE)
                drawBitmap(
                    decoded,
                    android.graphics.Rect(x, y, x + side, y + side),
                    android.graphics.Rect(0, 0, outSize, outSize),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
            }
            val stream = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, (quality * 100).roundToInt().coerceIn(1, 100), stream)
            out.recycle()
            EncodedImage(stream.toByteArray(), outSize, outSize)
        } finally {
            decoded.recycle()
        }
    }.getOrNull()

    private fun decodeScaled(bytes: ByteArray, maxDimension: Int): Bitmap? {
        val heif = looksLikeHeif(bytes)
        // HEIF/AVIF：BitmapFactory.inJustDecodeBounds 会打 HeifDecoderImpl getSize 且失败，必须走 ImageDecoder。
        if (Build.VERSION.SDK_INT >= 28 && heif) {
            imgSendLog.i { "decodeScaled path=ImageDecoder(heif) max=$maxDimension" }
            return imgSendTimed("decode.imageDecoder") { decodeWithImageDecoder(bytes, maxDimension) }
        }
        imgSendLog.i { "decodeScaled path=BitmapFactory first heif=$heif max=$maxDimension" }
        imgSendTimed("decode.bitmapFactory") { decodeWithBitmapFactory(bytes, maxDimension) }?.let { return it }
        // 未识别出的 HEIF 变体：Bounds 失败后再试 ImageDecoder（此时可能已打过一条 getSize 日志）。
        if (Build.VERSION.SDK_INT < 28) return null
        imgSendLog.w { "decodeScaled BitmapFactory miss, fallback ImageDecoder" }
        return imgSendTimed("decode.imageDecoder.fallback") { decodeWithImageDecoder(bytes, maxDimension) }
    }

    private fun hexHead(bytes: ByteArray, n: Int = 16): String =
        bytes.take(n).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /**
     * ISOBMFF `ftyp`：扫描 major / compatible brands。
     * 注意：不少机型 major 是 `isom`，真正的 `heic` 在兼容品牌列表里。
     */
    private fun looksLikeHeif(bytes: ByteArray): Boolean {
        if (bytes.size < 16) return false
        if (bytes[4] != 'f'.code.toByte() || bytes[5] != 't'.code.toByte() ||
            bytes[6] != 'y'.code.toByte() || bytes[7] != 'p'.code.toByte()
        ) {
            return false
        }
        var boxSize = ((bytes[0].toInt() and 0xff) shl 24) or
            ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)
        if (boxSize == 0) boxSize = bytes.size
        if (boxSize < 16) return false
        val end = minOf(bytes.size, boxSize).and(3.inv()) // 4 字节对齐
        var i = 8
        while (i + 4 <= end) {
            val brand = bytes.copyOfRange(i, i + 4).toString(Charsets.US_ASCII).lowercase()
            if (brand in HEIF_BRANDS) return true
            i += 4
        }
        return false
    }

    private fun decodeWithImageDecoder(bytes: ByteArray, maxDimension: Int): Bitmap? = runCatching {
        withImageSource(bytes) { source ->
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = minOf(1f, maxDimension.toFloat() / max(w, h))
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (w * scale).roundToInt().coerceAtLeast(1),
                        (h * scale).roundToInt().coerceAtLeast(1),
                    )
                }
                // HARDWARE bitmap 不能 Canvas / compress；ImageDecoder 会自动按 EXIF 摆正。
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
    }.getOrNull()

    private fun imageDecoderSize(bytes: ByteArray): Pair<Int, Int>? = runCatching {
        var size: Pair<Int, Int>? = null
        withImageSource(bytes) { source ->
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                size = info.size.width to info.size.height
                decoder.setTargetSize(1, 1)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }.recycle()
        }
        size?.takeIf { it.first > 0 && it.second > 0 }
    }.getOrNull()

    /**
     * API 31+ 的 [ImageDecoder.createSource] 要求 **direct** ByteBuffer（[ByteBuffer.wrap] 是堆内存，会直接失败）。
     * 失败后压缩返回 null → 原样发 HEIC → 又慢又会触发 Coil/BitmapFactory 的 getSize 日志。
     */
    private inline fun <T> withImageSource(bytes: ByteArray, block: (ImageDecoder.Source) -> T): T {
        if (Build.VERSION.SDK_INT >= 31) {
            return imgSendTimed("imageSource.directBuffer", "bytes=${bytes.size}") {
                val buf = ByteBuffer.allocateDirect(bytes.size)
                buf.put(bytes)
                buf.flip()
                block(ImageDecoder.createSource(buf))
            }
        }
        val tmp = File.createTempFile("goim_img_", ".heic")
        try {
            imgSendTimed("imageSource.writeTemp", "bytes=${bytes.size}") {
                tmp.outputStream().use { it.write(bytes) }
            }
            return imgSendTimed("imageSource.decodeFile") { block(ImageDecoder.createSource(tmp)) }
        } finally {
            tmp.delete()
        }
    }

    private fun decodeWithBitmapFactory(bytes: ByteArray, maxDimension: Int): Bitmap? {
        val bounds = imgSendTimed("bitmapFactory.bounds") { bitmapFactoryBounds(bytes) }
        if (bounds == null) {
            imgSendLog.w { "bitmapFactory.bounds miss (HeifDecoder getSize often lands here) outMime probe next" }
            return null
        }
        val (w, h) = bounds
        var sample = 1
        while (max(w, h) / (sample * 2) >= maxDimension) sample *= 2
        imgSendLog.i { "bitmapFactory.decode ${w}x${h} sample=$sample" }
        val decoded = imgSendTimed("bitmapFactory.decode") {
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } ?: return null
        val rotation = rotation(bytes)
        val scale = minOf(1f, maxDimension.toFloat() / max(decoded.width, decoded.height))
        if (scale >= 1f && rotation == 0) return decoded
        val matrix = Matrix().apply {
            postScale(scale, scale)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        val transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (transformed !== decoded) decoded.recycle()
        return transformed
    }

    private fun toJpeg(bitmap: Bitmap, quality: Float): EncodedImage {
        val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val out = ByteArrayOutputStream()
        opaque.compress(Bitmap.CompressFormat.JPEG, (quality * 100).roundToInt().coerceIn(1, 100), out)
        val result = EncodedImage(out.toByteArray(), opaque.width, opaque.height)
        opaque.recycle()
        return result
    }

    private fun bitmapFactoryBounds(bytes: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    private fun rotation(bytes: ByteArray): Int =
        runCatching { ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees }.getOrDefault(0)

    private val HEIF_BRANDS = setOf(
        "heic", "heix", "heif", "heim", "heis", "hevm", "hevs",
        "mif1", "msf1", "miaf", "avif", "avis",
    )
}

private class AndroidVoiceRecorder(private val context: Context) : VoiceRecorder {
    private var recorder: MediaRecorder? = null
    private var path: String? = null
    private var startedAt = 0L

    override val isSupported = true

    override fun start(outputPath: String): Boolean {
        cancel()
        return runCatching {
            File(outputPath).parentFile?.mkdirs()
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16_000)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(32_000)
            r.setOutputFile(outputPath)
            r.prepare()
            r.start()
            recorder = r
            path = outputPath
            startedAt = SystemClock.elapsedRealtime()
            true
        }.getOrElse {
            cancel()
            false
        }
    }

    override fun level(): Float = runCatching { (recorder?.maxAmplitude ?: 0) / 32_767f }.getOrDefault(0f).coerceIn(0f, 1f)

    override fun stop(): RecordedAudio? {
        val r = recorder ?: return null
        val p = path ?: return null
        val duration = SystemClock.elapsedRealtime() - startedAt
        recorder = null
        path = null
        // 过短时 MediaRecorder.stop 会抛异常且文件不可用
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        if (!ok) {
            File(p).delete()
            return null
        }
        return RecordedAudio(p, duration)
    }

    override fun cancel() {
        recorder?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
        path?.let { File(it).delete() }
        recorder = null
        path = null
    }
}

actual fun createVoiceRecorder(context: com.goim.kmp.common.PlatformContext): VoiceRecorder = AndroidVoiceRecorder(context)

private class AndroidVoicePlayer : VoicePlayer {
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    override fun play(key: String, path: String) {
        stop()
        val p = runCatching {
            MediaPlayer().apply {
                setDataSource(path)
                prepare()
                setOnCompletionListener { if (player === it) stop() }
                setOnErrorListener { mp, _, _ ->
                    if (player === mp) stop()
                    true
                }
                start()
            }
        }.getOrNull() ?: return
        player = p
        _state.value = PlaybackState(key, 0f)
        ticker = scope.launch {
            delay(100)
            while (isActive) {
                val mp = player ?: break
                if (!runCatching { mp.isPlaying }.getOrDefault(false)) {
                    stop()
                    break
                }
                val total = runCatching { mp.duration }.getOrDefault(0)
                val pos = runCatching { mp.currentPosition }.getOrDefault(0)
                if (total > 0) _state.value = PlaybackState(key, (pos.toFloat() / total).coerceIn(0f, 1f))
                delay(100)
            }
        }
    }

    override fun stop() {
        ticker?.cancel()
        ticker = null
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        _state.value = PlaybackState()
    }
}

actual fun createVoicePlayer(context: com.goim.kmp.common.PlatformContext): VoicePlayer = AndroidVoicePlayer()

actual fun mediaDurationSeconds(path: String): Int? = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(path)
        val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
        maxOf(1, (ms / 1000.0).roundToInt())
    } finally {
        r.release()
    }
}.getOrNull()
