package com.goim.kmp.feature.chat

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.datatransfer.DataFlavor
import androidx.compose.ui.Modifier
import com.goim.kmp.common.PlatformContext
import io.github.vinceglb.filekit.PlatformFile
import java.awt.Desktop
import java.io.File

@Composable
internal actual fun rememberPlatformContext(): PlatformContext = PlatformContext.INSTANCE

/** 桌面无运行时授权 API：macOS 首次打开录音线路时由系统弹窗。 */
@Composable
internal actual fun rememberMicrophonePermission(): suspend () -> Boolean = { true }

@Composable
internal actual fun rememberCameraCapture(onResult: (PlatformFile?) -> Unit): (() -> Unit)? = null

internal actual fun openWithSystem(path: String, displayName: String) {
    runCatching { Desktop.getDesktop().open(File(path)) }
}

@Composable
internal actual fun AnimatedSticker(bytes: ByteArray, modifier: Modifier) = SkiaAnimatedImage(bytes, modifier)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun rememberFileDropModifier(onHover: (Boolean) -> Unit, onDrop: (List<PlatformFile>) -> Unit): Modifier {
    val hover by rememberUpdatedState(onHover)
    val drop by rememberUpdatedState(onDrop)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = hover(true)
            override fun onExited(event: DragAndDropEvent) = hover(false)
            override fun onEnded(event: DragAndDropEvent) = hover(false)
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover(false)
                val files = droppedFiles(event)
                if (files.isEmpty()) return false
                drop(files.map(::PlatformFile))
                return true
            }
        }
    }
    return Modifier.dragAndDropTarget(shouldStartDragAndDrop = ::hasFileFlavor, target = target)
}

@OptIn(ExperimentalComposeUiApi::class)
private fun hasFileFlavor(event: DragAndDropEvent): Boolean =
    runCatching { event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) }.getOrDefault(false)

@OptIn(ExperimentalComposeUiApi::class)
private fun droppedFiles(event: DragAndDropEvent): List<File> = runCatching {
    val transferable = event.awtTransferable
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
    (transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>().filter { it.isFile }
}.getOrDefault(emptyList())

internal actual val usesDesktopComposer: Boolean = true
