package com.goim.kmp.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.goim.kmp.common.PlatformContext
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.rememberCameraPickerLauncher
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.Foundation.NSURL
import platform.QuickLook.QLPreviewController
import platform.QuickLook.QLPreviewControllerDataSourceProtocol
import platform.QuickLook.QLPreviewItemProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.darwin.NSInteger
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

@Composable
internal actual fun rememberPlatformContext(): PlatformContext = PlatformContext.INSTANCE

@Composable
internal actual fun rememberMicrophonePermission(): suspend () -> Boolean = {
    val session = AVAudioSession.sharedInstance()
    if (session.recordPermission == AVAudioSessionRecordPermissionGranted) {
        true
    } else {
        suspendCancellableCoroutine { cont ->
            session.requestRecordPermission { granted ->
                dispatch_async(dispatch_get_main_queue()) { if (cont.isActive) cont.resume(granted) }
            }
        }
    }
}

@Composable
internal actual fun rememberCameraCapture(onResult: (PlatformFile?) -> Unit): (() -> Unit)? {
    val launcher = rememberCameraPickerLauncher(onResult = onResult)
    return { launcher.launch() }
}

private class PreviewItem(private val url: NSURL, private val title: String) : NSObject(), QLPreviewItemProtocol {
    override fun previewItemURL(): NSURL = url

    override fun previewItemTitle(): String = title
}

private class PreviewSource(private val item: PreviewItem) : NSObject(), QLPreviewControllerDataSourceProtocol {
    override fun numberOfPreviewItemsInPreviewController(controller: QLPreviewController): NSInteger = 1

    override fun previewController(controller: QLPreviewController, previewItemAtIndex: NSInteger): QLPreviewItemProtocol = item
}

/** `dataSource` 是 weak 引用：保持到下一次预览。 */
private var retainedSource: PreviewSource? = null

/** Quick Look 预览（含视频播放、PDF / Office），与 iOS 原生端一致。 */
internal actual fun openWithSystem(path: String, displayName: String) {
    val source = PreviewSource(PreviewItem(NSURL.fileURLWithPath(path), displayName))
    retainedSource = source
    val controller = QLPreviewController()
    controller.dataSource = source
    topViewController()?.presentViewController(controller, animated = true, completion = null)
}

private fun topViewController(): UIViewController? {
    @Suppress("DEPRECATION")
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}

@Composable
internal actual fun AnimatedSticker(bytes: ByteArray, modifier: Modifier) = SkiaAnimatedImage(bytes, modifier)

@Composable
internal actual fun rememberFileDropModifier(onHover: (Boolean) -> Unit, onDrop: (List<PlatformFile>) -> Unit): Modifier = Modifier

internal actual val usesDesktopComposer: Boolean = false
