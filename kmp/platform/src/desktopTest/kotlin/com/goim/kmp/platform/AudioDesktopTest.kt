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
}
