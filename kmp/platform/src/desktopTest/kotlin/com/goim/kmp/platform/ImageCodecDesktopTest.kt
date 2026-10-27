package com.goim.kmp.platform

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImageCodecDesktopTest {
    /** 200×100：左半红、右半蓝。 */
    private fun halves(): ByteArray {
        val image = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color.RED
            fillRect(0, 0, 100, 100)
            color = Color.BLUE
            fillRect(100, 0, 100, 100)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun centerColor(jpeg: ByteArray): Color {
        val image = ImageIO.read(ByteArrayInputStream(jpeg))
        return Color(image.getRGB(image.width / 2, image.height / 2))
    }

    @Test
    fun cropsRequestedSquareAndScales() {
        val right = assertNotNull(ImageCodec.cropSquareJpeg(halves(), left = 100, top = 0, size = 100, outSize = 64, quality = 0.9f))
        assertEquals(64 to 64, right.width to right.height)
        val c = centerColor(right.bytes)
        assertTrue(c.blue > 200 && c.red < 60, "expected blue, got $c")

        val left = assertNotNull(ImageCodec.cropSquareJpeg(halves(), left = 0, top = 0, size = 100, outSize = 64, quality = 0.9f))
        assertTrue(centerColor(left.bytes).red > 200)
    }

    @Test
    fun clampsOutOfBoundsRect() {
        val out = assertNotNull(ImageCodec.cropSquareJpeg(halves(), left = 180, top = 50, size = 500, outSize = 32, quality = 0.9f))
        assertEquals(32, out.width)
        assertTrue(centerColor(out.bytes).blue > 200, "clamped to the rightmost 100×100 square")
    }
}
