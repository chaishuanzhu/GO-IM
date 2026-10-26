package com.goim.kmp.feature.chat

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.File

internal actual fun fullScreenDialogProperties(): DialogProperties = DialogProperties(usePlatformDefaultWidth = false)

internal actual val inAppFilePreview: Boolean = true

internal actual suspend fun readTextPreview(path: String, maxBytes: Int): TextPreviewContent? =
    withContext(Dispatchers.IO) { readTextPreviewJvm(File(path), maxBytes) }

internal actual suspend fun openPdf(path: String): PdfDocumentHandle? = withContext(Dispatchers.IO) {
    runCatching {
        val doc = Loader.loadPDF(File(path))
        val aspects = doc.pages.map { page -> displaySize(page).let { (w, h) -> if (w > 0f && h > 0f) h / w else 1f } }
        DesktopPdf(doc, aspects)
    }.getOrNull()
}

/** 按页面旋转后的显示宽高。 */
private fun displaySize(page: PDPage): Pair<Float, Float> {
    val box = page.cropBox
    return if (page.rotation % 180 != 0) box.height to box.width else box.width to box.height
}

private class DesktopPdf(private val doc: PDDocument, override val pageAspects: List<Float>) : PdfDocumentHandle {
    private val renderer = PDFRenderer(doc)
    private val mutex = Mutex()
    private var closed = false

    override suspend fun render(page: Int, widthPx: Int): ImageBitmap? = mutex.withLock {
        if (closed) return@withLock null
        withContext(Dispatchers.IO) {
            runCatching {
                val width = displaySize(doc.getPage(page)).first
                renderer.renderImage(page, widthPx / width, ImageType.RGB).toComposeImageBitmap()
            }.getOrNull()
        }
    }

    override fun close() {
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                closed = true
                runCatching { doc.close() }
            }
        }
    }
}
