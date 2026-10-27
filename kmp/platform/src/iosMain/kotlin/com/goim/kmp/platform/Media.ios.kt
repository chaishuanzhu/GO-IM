@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.goim.kmp.platform

import com.goim.kmp.common.PlatformContext
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVEncoderBitRateKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFAudio.setActive
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.duration
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.numberWithUnsignedInt
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIRectFill
import platform.posix.memcpy
import kotlin.math.max
import kotlin.math.roundToInt

internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

internal fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    val out = ByteArray(n)
    if (n > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

actual object ImageCodec {
    actual fun dimensions(bytes: ByteArray): Pair<Int, Int>? = autoreleasepool {
        val image = UIImage.imageWithData(bytes.toNSData()) ?: return@autoreleasepool null
        image.size.useContents { (width * image.scale).roundToInt() to (height * image.scale).roundToInt() }
            .takeIf { it.first > 0 && it.second > 0 }
    }

    /** UIImage 绘制时会应用 imageOrientation，等同摆正 EXIF；HEIC 也能解码。 */
    actual fun encodeJpeg(bytes: ByteArray, maxDimension: Int, quality: Float): EncodedImage? = autoreleasepool {
        val image = UIImage.imageWithData(bytes.toNSData()) ?: return@autoreleasepool null
        val (w, h) = image.size.useContents { width * image.scale to height * image.scale }
        if (w <= 0 || h <= 0) return@autoreleasepool null
        val scale = minOf(1.0, maxDimension / max(w, h))
        val tw = max(1, (w * scale).roundToInt()).toDouble()
        val th = max(1, (h * scale).roundToInt()).toDouble()
        val format = UIGraphicsImageRendererFormat.defaultFormat().apply {
            setScale(1.0)
            setOpaque(true)
        }
        val renderer = UIGraphicsImageRenderer(size = CGSizeMake(tw, th), format = format)
        val rendered = renderer.imageWithActions { _ ->
            UIColor.whiteColor.setFill()
            UIRectFill(CGRectMake(0.0, 0.0, tw, th))
            image.drawInRect(CGRectMake(0.0, 0.0, tw, th))
        }
        val data = UIImageJPEGRepresentation(rendered, quality.toDouble()) ?: return@autoreleasepool null
        EncodedImage(data.toByteArray(), tw.toInt(), th.toInt())
    }

    actual fun cropSquareJpeg(bytes: ByteArray, left: Int, top: Int, size: Int, outSize: Int, quality: Float): EncodedImage? =
        autoreleasepool {
            val image = UIImage.imageWithData(bytes.toNSData()) ?: return@autoreleasepool null
            val (w, h) = image.size.useContents { width * image.scale to height * image.scale }
            if (w <= 0 || h <= 0) return@autoreleasepool null
            val side = size.toDouble().coerceIn(1.0, minOf(w, h))
            val x = left.toDouble().coerceIn(0.0, w - side)
            val y = top.toDouble().coerceIn(0.0, h - side)
            val k = outSize / side
            val target = outSize.toDouble()
            val format = UIGraphicsImageRendererFormat.defaultFormat().apply {
                setScale(1.0)
                setOpaque(true)
            }
            val renderer = UIGraphicsImageRenderer(size = CGSizeMake(target, target), format = format)
            val rendered = renderer.imageWithActions { _ ->
                UIColor.whiteColor.setFill()
                UIRectFill(CGRectMake(0.0, 0.0, target, target))
                image.drawInRect(CGRectMake(-x * k, -y * k, w * k, h * k))
            }
            val data = UIImageJPEGRepresentation(rendered, quality.toDouble()) ?: return@autoreleasepool null
            EncodedImage(data.toByteArray(), outSize, outSize)
        }
}

private class IosVoiceRecorder : VoiceRecorder {
    private var recorder: AVAudioRecorder? = null

    override val isSupported = true

    override fun start(outputPath: String): Boolean {
        cancel()
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayAndRecord, AVAudioSessionCategoryOptionDefaultToSpeaker, null)
        session.setActive(true, null)
        val settings = mapOf<Any?, Any?>(
            AVFormatIDKey to NSNumber.numberWithUnsignedInt(kAudioFormatMPEG4AAC),
            AVSampleRateKey to 16_000.0,
            AVNumberOfChannelsKey to 1,
            AVEncoderBitRateKey to 32_000,
        )
        val r = AVAudioRecorder(uRL = NSURL.fileURLWithPath(outputPath), settings = settings, error = null)
        r.setMeteringEnabled(true)
        if (!r.prepareToRecord() || !r.record()) return false
        recorder = r
        return true
    }

    override fun level(): Float {
        val r = recorder ?: return 0f
        r.updateMeters()
        val db = r.averagePowerForChannel(0u)
        return ((db + 50f) / 50f).coerceIn(0f, 1f)
    }

    override fun stop(): RecordedAudio? {
        val r = recorder ?: return null
        recorder = null
        val seconds = r.currentTime
        r.stop()
        val path = r.url.path ?: return null
        return RecordedAudio(path, (seconds * 1000).toLong())
    }

    override fun cancel() {
        recorder?.let {
            it.stop()
            it.deleteRecording()
        }
        recorder = null
    }
}

actual fun createVoiceRecorder(context: PlatformContext): VoiceRecorder = IosVoiceRecorder()

private class IosVoicePlayer : VoicePlayer {
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.Main)
    private var player: AVAudioPlayer? = null
    private var ticker: Job? = null

    override fun play(key: String, path: String) {
        stop()
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, null)
        session.setActive(true, null)
        val p = AVAudioPlayer(contentsOfURL = NSURL.fileURLWithPath(path), error = null)
        if (!p.prepareToPlay() || !p.play()) return
        player = p
        _state.value = PlaybackState(key, 0f)
        ticker = scope.launch {
            delay(100)
            while (isActive) {
                val current = player ?: break
                if (!current.playing) {
                    stop()
                    break
                }
                if (current.duration > 0) _state.value = PlaybackState(key, (current.currentTime / current.duration).toFloat())
                delay(100)
            }
        }
    }

    override fun stop() {
        ticker?.cancel()
        ticker = null
        player?.stop()
        player = null
        _state.value = PlaybackState()
    }
}

actual fun createVoicePlayer(context: PlatformContext): VoicePlayer = IosVoicePlayer()

actual fun mediaDurationSeconds(path: String): Int? {
    val asset = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(path), null)
    val seconds = CMTimeGetSeconds(asset.duration)
    if (seconds.isNaN() || seconds <= 0.0) return null
    return maxOf(1, seconds.roundToInt())
}
