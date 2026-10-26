package com.goim.kmp.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import com.goim.kmp.common.PlatformContext
import io.github.vinceglb.filekit.PlatformFile

@Composable
internal expect fun rememberPlatformContext(): PlatformContext

/** 返回申请麦克风权限的挂起函数（已授权直接返回 true）。 */
@Composable
internal expect fun rememberMicrophonePermission(): suspend () -> Boolean

/** 拍照；桌面端不支持时返回 null。 */
@Composable
internal expect fun rememberCameraCapture(onResult: (PlatformFile?) -> Unit): (() -> Unit)?

/** 用系统能力打开附件：Android 外部应用、iOS Quick Look、桌面默认程序。 */
internal expect fun openWithSystem(path: String, displayName: String)

/** 全屏弹窗：iOS 铺满安全区之外的区域（内容自行处理 insets），其他平台只取消默认宽度。 */
internal expect fun fullScreenDialogProperties(): DialogProperties

/** 文本 / PDF 是否在应用内预览；iOS 统一走 Quick Look，为 false。 */
internal expect val inAppFilePreview: Boolean

/** 读取文件开头至多 [maxBytes] 字节并解码为文本；读取失败返回 null。 */
internal expect suspend fun readTextPreview(path: String, maxBytes: Int): TextPreviewContent?

/** 打开 PDF；不支持或文件损坏时返回 null。 */
internal expect suspend fun openPdf(path: String): PdfDocumentHandle?

/** 桌面：接收从系统拖入的文件（[onHover] 用于高亮投放区）；其他平台返回空 Modifier。 */
@Composable
internal expect fun rememberFileDropModifier(onHover: (Boolean) -> Unit, onDrop: (List<PlatformFile>) -> Unit): Modifier

/** 桌面端用「工具栏 + 多行编辑区 + 发送按钮」的输入区；移动端用单行气泡输入框 + 底部面板。 */
internal expect val usesDesktopComposer: Boolean

/** GIF / WebP 动图；静态图也可用。 */
@Composable
internal expect fun AnimatedSticker(bytes: ByteArray, modifier: Modifier = Modifier)
