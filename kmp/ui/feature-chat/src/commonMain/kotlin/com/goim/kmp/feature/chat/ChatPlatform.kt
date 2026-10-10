package com.goim.kmp.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

/** 桌面：接收从系统拖入的文件（[onHover] 用于高亮投放区）；其他平台返回空 Modifier。 */
@Composable
internal expect fun rememberFileDropModifier(onHover: (Boolean) -> Unit, onDrop: (List<PlatformFile>) -> Unit): Modifier

/** 桌面端用「工具栏 + 多行编辑区 + 发送按钮」的输入区；移动端用单行气泡输入框 + 底部面板。 */
internal expect val usesDesktopComposer: Boolean

/** GIF / WebP 动图；静态图也可用。 */
@Composable
internal expect fun AnimatedSticker(bytes: ByteArray, modifier: Modifier = Modifier)
