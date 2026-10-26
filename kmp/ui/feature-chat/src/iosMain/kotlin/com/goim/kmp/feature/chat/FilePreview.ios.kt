package com.goim.kmp.feature.chat

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun fullScreenDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false, usePlatformInsets = false)

internal actual val inAppFilePreview: Boolean = false

internal actual suspend fun readTextPreview(path: String, maxBytes: Int): TextPreviewContent? = null

internal actual suspend fun openPdf(path: String): PdfDocumentHandle? = null
