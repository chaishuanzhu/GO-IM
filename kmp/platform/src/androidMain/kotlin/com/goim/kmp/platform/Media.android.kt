package com.goim.kmp.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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
import kotlin.math.max
import kotlin.math.roundToInt

actual object ImageCodec {
    actual fun dimensions(bytes: ByteArray): Pair<Int, Int>? {
        val bounds = bounds(bytes) ?: return null
        return if (rotation(bytes) % 180 != 0) bounds.second to bounds.first else bounds
    }

    actual fun encodeJpeg(bytes: ByteArray, maxDimension: Int, quality: Float): EncodedImage? = runCatching {
        val (w, h) = bounds(bytes) ?: return null
        var sample = 1
        while (max(w, h) / (sample * 2) >= maxDimension) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val rotation = rotation(bytes)
        val scale = minOf(1f, maxDimension.toFloat() / max(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        val transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val opaque = Bitmap.createBitmap(transformed.width, transformed.height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply {
            drawColor(Color.WHITE)
            drawBitmap(transformed, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val out = ByteArrayOutputStream()
        opaque.compress(Bitmap.CompressFormat.JPEG, (quality * 100).roundToInt().coerceIn(1, 100), out)
        val result = EncodedImage(out.toByteArray(), opaque.width, opaque.height)
        if (transformed !== decoded) transformed.recycle()
        decoded.recycle()
        opaque.recycle()
        result
    }.getOrNull()

    private fun bounds(bytes: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    private fun rotation(bytes: ByteArray): Int =
        runCatching { ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees }.getOrDefault(0)
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
                setOnCompletionListener { stop() }
                start()
            }
        }.getOrNull() ?: return
        player = p
        _state.value = PlaybackState(key, 0f)
        ticker = scope.launch {
            while (isActive) {
                val mp = player ?: break
                val total = runCatching { mp.duration }.getOrDefault(0)
                val pos = runCatching { mp.currentPosition }.getOrDefault(0)
                if (total > 0) _state.value = PlaybackState(key, pos.toFloat() / total)
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
