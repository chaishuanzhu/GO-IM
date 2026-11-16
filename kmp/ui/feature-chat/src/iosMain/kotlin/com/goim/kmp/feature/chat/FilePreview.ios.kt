package com.goim.kmp.feature.chat

internal actual val inAppFilePreview: Boolean = false

internal actual suspend fun readTextPreview(path: String, maxBytes: Int): TextPreviewContent? = null

internal actual suspend fun openPdf(path: String): PdfDocumentHandle? = null
