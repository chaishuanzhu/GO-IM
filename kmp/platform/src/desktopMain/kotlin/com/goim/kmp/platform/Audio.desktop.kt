package com.goim.kmp.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineEvent
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread
import kotlin.math.sqrt

internal val VoiceFormat = AudioFormat(16_000f, 16, 1, true, false)

/** 时长上限，与 iOS 录音一致。 */
private const val MAX_RECORD_MILLIS = 60_000L

/** JavaSound 录 16 kHz 单声道 16-bit PCM，停止时写成 WAV（JVM 无 AAC 编码器）。 */
internal class WavVoiceRecorder : VoiceRecorder {
    override val isSupported: Boolean =
        runCatching { AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, VoiceFormat)) }.getOrDefault(false)
    override val fileExtension = "wav"

    private var line: TargetDataLine? = null
    private var reader: Thread? = null
    private var outputPath: String? = null
    private val pcm = ByteArrayOutputStream()

    @Volatile private var currentLevel = 0f

    override fun start(outputPath: String): Boolean {
        cancel()
        val opened = runCatching {
            (AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, VoiceFormat)) as TargetDataLine).apply {
                open(VoiceFormat)
                start()
            }
        }.getOrNull() ?: return false
        line = opened
        this.outputPath = outputPath
        pcm.reset()
        val maxBytes = (VoiceFormat.frameRate * VoiceFormat.frameSize * MAX_RECORD_MILLIS / 1000).toInt()
        reader = thread(name = "goim-voice-recorder", isDaemon = true) {
            val buffer = ByteArray(VoiceFormat.frameSize * 1600)
            while (opened.isOpen) {
                val n = runCatching { opened.read(buffer, 0, buffer.size) }.getOrDefault(-1)
                if (n <= 0) break
                synchronized(pcm) { if (pcm.size() < maxBytes) pcm.write(buffer, 0, n) }
                currentLevel = rms(buffer, n)
            }
        }
        return true
    }

    override fun level(): Float = currentLevel

    override fun stop(): RecordedAudio? {
        val path = outputPath ?: return null
        closeLine()
        val bytes = synchronized(pcm) { pcm.toByteArray() }
        reset()
        if (bytes.isEmpty()) return null
        return runCatching {
            val frames = bytes.size / VoiceFormat.frameSize
            AudioInputStream(ByteArrayInputStream(bytes), VoiceFormat, frames.toLong()).use {
                AudioSystem.write(it, AudioFileFormat.Type.WAVE, File(path))
            }
            RecordedAudio(path, frames * 1000L / VoiceFormat.frameRate.toLong(), mime = "audio/wav")
        }.getOrNull()
    }

    override fun cancel() {
        closeLine()
        reset()
    }

    private fun closeLine() {
        line?.let {
            runCatching { it.stop() }
            runCatching { it.close() }
        }
        reader?.join(500)
        line = null
        reader = null
    }

    private fun reset() {
        outputPath = null
        currentLevel = 0f
        synchronized(pcm) { pcm.reset() }
    }

    private fun rms(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            sum += sample.toDouble() * sample
            count++
            i += 2
        }
        if (count == 0) return 0f
        // 与移动端 dB 计量大致对齐：放大后截断到 0..1
        return (sqrt(sum / count) / Short.MAX_VALUE * 4).toFloat().coerceIn(0f, 1f)
    }
}

/** WAV 用 `Clip` 内置播放并追踪进度；其他格式（AAC 等）交给系统默认播放器。 */
internal class DesktopVoicePlayer : VoicePlayer {
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clip: Clip? = null
    private var ticker: Job? = null

    @Synchronized
    override fun play(key: String, path: String) {
        val wasPlaying = _state.value.key == key
        stop()
        if (wasPlaying) return
        val file = File(path)
        val started = runCatching {
            AudioSystem.getAudioInputStream(file).use { stream ->
                AudioSystem.getClip().apply {
                    open(stream)
                    addLineListener { event ->
                        // 自然播完时 framePosition 常比 frameLength 少几帧，不能用相等判断；手动 stop 时 clip 已置空
                        if (event.type == LineEvent.Type.STOP) {
                            // 不在 JavaSound 回调线程里关闭 Clip
                            scope.launch { if (clip === this@apply) this@DesktopVoicePlayer.stop() }
                        }
                    }
                    start()
                }
            }
        }.getOrNull()
        if (started == null) {
            runCatching { Desktop.getDesktop().open(file) }
            return
        }
        clip = started
        _state.value = PlaybackState(key, 0f)
        val deadline = System.currentTimeMillis() + started.microsecondLength / 1000 + 1000
        ticker = scope.launch {
            var sawRunning = false
            while (isActive) {
                val c = clip ?: break
                if (c.isRunning) sawRunning = true
                // STOP 事件偶尔收不到：已开始后不再运行，或超过时长 1 秒仍未结束，都视为播完
                if ((sawRunning && !c.isRunning) || System.currentTimeMillis() > deadline) {
                    if (clip === c) stop()
                    break
                }
                if (c.frameLength > 0) _state.value = PlaybackState(key, (c.framePosition.toFloat() / c.frameLength).coerceIn(0f, 1f))
                delay(100)
            }
        }
    }

    @Synchronized
    override fun stop() {
        ticker?.cancel()
        ticker = null
        clip?.let {
            clip = null
            runCatching { it.stop() }
            runCatching { it.close() }
        }
        _state.value = PlaybackState()
    }
}

/** WAV / AIFF 等 JavaSound 可读格式；其他返回 null。 */
internal fun audioDurationSeconds(path: String): Int? = runCatching {
    val format = AudioSystem.getAudioFileFormat(File(path))
    val frames = format.frameLength
    if (frames <= 0) return null
    maxOf(1, Math.round(frames / format.format.frameRate))
}.getOrNull()
