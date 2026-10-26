package com.goim.kmp.feature.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.goim.kmp.designsystem.GoImDimens
import com.goim.kmp.designsystem.GoImElevation
import com.goim.kmp.designsystem.GoImIcons
import com.goim.kmp.designsystem.GoImSize
import com.goim.kmp.designsystem.GoImSpacing
import com.goim.kmp.model.FileMeta

internal enum class FilePreviewKind { Text, Code, Pdf }

internal data class FilePreviewRequest(val path: String, val meta: FileMeta, val kind: FilePreviewKind)

/** 文本预览最多读取的字节数，超出部分不显示。 */
internal const val TEXT_PREVIEW_MAX_BYTES = 1 shl 20

/** PDF 单页渲染的最大像素宽度，限制内存占用。 */
private const val PDF_MAX_RENDER_WIDTH = 2048

private val PLAIN_TEXT_EXTENSIONS = setOf("txt", "text", "md", "markdown", "csv", "tsv", "log")

private val CODE_EXTENSIONS = setOf(
    "json", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg", "properties", "env",
    "kt", "kts", "java", "go", "py", "js", "ts", "jsx", "tsx", "swift", "c", "cc", "cpp", "h", "hpp",
    "cs", "rs", "rb", "php", "lua", "sh", "bash", "zsh", "bat", "ps1", "sql", "html", "htm", "css",
    "scss", "gradle", "proto", "vue", "dart",
)

private val CODE_MIMES = setOf(
    "application/json", "application/xml", "application/javascript", "application/x-sh",
    "application/x-yaml", "application/yaml", "application/toml", "application/sql",
)

/** 能在应用内预览的文件类型；null 表示交给系统打开。 */
internal fun filePreviewKind(meta: FileMeta): FilePreviewKind? {
    val mime = meta.mime.substringBefore(';').trim().lowercase()
    val ext = meta.name.substringAfterLast('.', "").lowercase()
    return when {
        mime == "application/pdf" || ext == "pdf" -> FilePreviewKind.Pdf
        ext in CODE_EXTENSIONS || mime in CODE_MIMES -> FilePreviewKind.Code
        ext in PLAIN_TEXT_EXTENSIONS || mime.startsWith("text/") -> FilePreviewKind.Text
        else -> null
    }
}

internal class TextPreviewContent(val text: String, val truncated: Boolean)

/** 已打开的 PDF 文档；页面渲染串行执行。 */
internal interface PdfDocumentHandle {
    /** 每页的高宽比。 */
    val pageAspects: List<Float>

    suspend fun render(page: Int, widthPx: Int): ImageBitmap?

    fun close()
}

private sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data object Failed : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilePreviewDialog(request: FilePreviewRequest, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val wide = maxWidth >= GoImDimens.expandedBreakpoint
            Surface(
                modifier = if (wide) {
                    Modifier.widthIn(max = GoImDimens.filePreviewMaxWidth).fillMaxWidth(0.85f).fillMaxHeight(0.9f)
                } else {
                    Modifier.fillMaxSize()
                },
                shape = if (wide) MaterialTheme.shapes.extraLarge else RectangleShape,
                shadowElevation = if (wide) GoImElevation.level3 else GoImElevation.level0,
            ) {
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    TopAppBar(
                        title = {
                            Text(
                                request.meta.name.ifBlank { "文件" },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "关闭") }
                        },
                        actions = {
                            IconButton(onClick = { openWithSystem(request.path, request.meta.name) }) {
                                Icon(GoImIcons.OpenInNew, contentDescription = "用其他应用打开")
                            }
                        },
                        windowInsets = WindowInsets(0, 0, 0, 0),
                    )
                    HorizontalDivider()
                    when (request.kind) {
                        FilePreviewKind.Text -> TextFilePreview(request.path, monospace = false)
                        FilePreviewKind.Code -> TextFilePreview(request.path, monospace = true)
                        FilePreviewKind.Pdf -> PdfFilePreview(request.path)
                    }
                }
            }
        }
    }
}

@Composable
private fun TextFilePreview(path: String, monospace: Boolean) {
    val state by produceState<Loadable<TextPreviewContent>>(Loadable.Loading, path) {
        value = readTextPreview(path, TEXT_PREVIEW_MAX_BYTES)?.let { Loadable.Ready(it) } ?: Loadable.Failed
    }
    when (val s = state) {
        Loadable.Loading -> PreviewLoading()
        Loadable.Failed -> PreviewFailed("无法读取文件")
        is Loadable.Ready -> {
            val lines = remember(s.value) { splitPreviewLines(s.value.text) }
            val style = if (monospace) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            }
            SelectionContainer {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(GoImSpacing.s4),
                ) {
                    if (s.value.truncated) {
                        item {
                            Text(
                                "文件较大，仅显示前 1 MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(bottom = GoImSpacing.s3),
                            )
                        }
                    }
                    items(lines) { line -> Text(line, style = style) }
                }
            }
        }
    }
}

/** 按行切分；超长行再切段，避免单个 Text 过大。 */
internal fun splitPreviewLines(text: String, maxLineChars: Int = 2000): List<String> =
    text.removePrefix("\uFEFF").lines().flatMap { line ->
        if (line.length <= maxLineChars) listOf(line) else line.chunked(maxLineChars)
    }

@Composable
private fun PdfFilePreview(path: String) {
    val state by produceState<Loadable<PdfDocumentHandle>>(Loadable.Loading, path) {
        val doc = openPdf(path)
        value = doc?.let { Loadable.Ready(it) } ?: Loadable.Failed
        awaitDispose { doc?.close() }
    }
    when (val s = state) {
        Loadable.Loading -> PreviewLoading()
        Loadable.Failed -> PreviewFailed("无法打开 PDF")
        is Loadable.Ready -> {
            val doc = s.value
            if (doc.pageAspects.isEmpty()) {
                PreviewFailed("PDF 没有页面")
                return
            }
            BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                val pageWidth = (maxWidth - GoImSpacing.s4 * 2).coerceIn(1.dp, GoImDimens.filePreviewMaxWidth)
                val widthPx = with(LocalDensity.current) { pageWidth.roundToPx() }.coerceAtMost(PDF_MAX_RENDER_WIDTH)
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(GoImSpacing.s4),
                    verticalArrangement = Arrangement.spacedBy(GoImSpacing.s3),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(doc.pageAspects.size) { index ->
                        PdfPage(doc, index, widthPx, Modifier.width(pageWidth).aspectRatio(1f / doc.pageAspects[index]))
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(doc: PdfDocumentHandle, index: Int, widthPx: Int, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, doc, index, widthPx) { value = doc.render(index, widthPx) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest), contentAlignment = Alignment.Center) {
        val page = bitmap
        if (page != null) {
            Image(page, contentDescription = "第 ${index + 1} 页", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize())
        } else {
            CircularProgressIndicator(Modifier.size(GoImSize.iconLg))
        }
    }
}

@Composable
private fun PreviewLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun PreviewFailed(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
