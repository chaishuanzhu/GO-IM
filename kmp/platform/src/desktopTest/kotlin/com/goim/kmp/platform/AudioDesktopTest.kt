package com.goim.kmp.platform

import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioDesktopTest {
    @Test
    fun wavDurationRoundsAndUnknownFormatsAreNull() {
        val wav = File.createTempFile("goim-voice", ".wav")
        val frames = (VoiceFormat.frameRate * 2.6).toInt()
        AudioInputStream(ByteArrayInputStream(ByteArray(frames * VoiceFormat.frameSize)), VoiceFormat, frames.toLong()).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, wav)
        }
        assertEquals(3, mediaDurationSeconds(wav.path))

        val m4a = File.createTempFile("goim-voice", ".m4a").apply { writeBytes(ByteArray(64)) }
        assertNull(mediaDurationSeconds(m4a.path))
        wav.delete()
        m4a.delete()
    }

    @Test
    fun playerResetsStateWhenPlaybackEnds() {
        // 无音频输出设备（CI）时跳过，否则会退回系统播放器
        if (runCatching { AudioSystem.getClip().close() }.isFailure) return
        val wav = File.createTempFile("goim-voice", ".wav")
        val frames = (VoiceFormat.frameRate * 0.5).toInt()
        AudioInputStream(ByteArrayInputStream(ByteArray(frames * VoiceFormat.frameSize)), VoiceFormat, frames.toLong()).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, wav)
        }
        val player = DesktopVoicePlayer()
        player.play("m1", wav.path)
        assertEquals("m1", player.state.value.key)
        val deadline = System.currentTimeMillis() + 3_000
        while (player.state.value.key != null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertNull(player.state.value.key)
        wav.delete()
    }
}
