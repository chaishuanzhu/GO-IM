package com.goim.kmp.feature.chat

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

internal actual fun fullScreenDialogProperties(): DialogProperties = DialogProperties(usePlatformDefaultWidth = false)

internal actual val inAppFilePreview: Boolean = true

internal actual suspend fun readTextPreview(path: String, maxBytes: Int): TextPreviewContent? =
    withContext(Dispatchers.IO) { readTextPreviewJvm(File(path), maxBytes) }

internal actual suspend fun openPdf(path: String): PdfDocumentHandle? = withContext(Dispatchers.IO) {
    var fd: ParcelFileDescriptor? = null
    try {
        fd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd)
        val aspects = (0 until renderer.pageCount).map { index ->
            val page = renderer.openPage(index)
            try {
                if (page.width > 0 && page.height > 0) page.height.toFloat() / page.width else 1f
            } finally {
                page.close()
            }
        }
        AndroidPdf(fd, renderer, aspects)
    } catch (_: Exception) {
        runCatching { fd?.close() }
        null
    }
}

private class AndroidPdf(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    override val pageAspects: List<Float>,
) : PdfDocumentHandle {
    // PdfRenderer 同一时刻只能打开一页
    private val mutex = Mutex()
    private var closed = false

    override suspend fun render(page: Int, widthPx: Int): ImageBitmap? = mutex.withLock {
        if (closed) return@withLock null
        withContext(Dispatchers.IO) {
            runCatching {
                val pdfPage = renderer.openPage(page)
                try {
                    val width = widthPx.coerceAtLeast(1)
                    val height = (width * pdfPage.height.toFloat() / pdfPage.width).roundToInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.asImageBitmap()
                } finally {
                    pdfPage.close()
                }
            }.getOrNull()
        }
    }

    override fun close() {
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                closed = true
                runCatching { renderer.close() }
                runCatching { fd.close() }
            }
        }
    }
}
