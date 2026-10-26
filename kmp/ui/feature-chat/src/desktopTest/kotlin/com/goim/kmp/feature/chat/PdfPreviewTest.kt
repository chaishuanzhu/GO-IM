package com.goim.kmp.feature.chat

import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PdfPreviewTest {
    @Test
    fun rendersPagesAtRequestedWidth() = runTest {
        val file = File.createTempFile("preview", ".pdf")
        try {
            PDDocument().use { doc ->
                doc.addPage(PDPage(PDRectangle.A4))
                doc.addPage(PDPage(PDRectangle(200f, 100f)))
                doc.save(file)
            }
            val pdf = assertNotNull(openPdf(file.path))
            assertEquals(2, pdf.pageAspects.size)
            assertEquals(0.5f, pdf.pageAspects[1], 0.01f)
            val bitmap = assertNotNull(pdf.render(1, 400))
            assertEquals(400, bitmap.width)
            assertEquals(200, bitmap.height)
            pdf.close()
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsNonPdf() = runTest {
        val file = File.createTempFile("broken", ".pdf").apply { writeText("not a pdf") }
        try {
            assertNull(openPdf(file.path))
        } finally {
            file.delete()
        }
    }
}
