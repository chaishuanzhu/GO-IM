package com.goim.kmp.feature.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import com.goim.kmp.common.PlatformContext
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.rememberCameraPickerLauncher
import io.github.vinceglb.filekit.dialogs.openFileWithDefaultApplication
import kotlinx.coroutines.CompletableDeferred

@Composable
internal actual fun rememberPlatformContext(): PlatformContext = LocalContext.current

@Composable
internal actual fun rememberMicrophonePermission(): suspend () -> Boolean {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending[0]?.complete(granted)
        pending[0] = null
    }
    return remember(context, launcher) {
        suspend {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                true
            } else {
                val wait = CompletableDeferred<Boolean>()
                pending[0] = wait
                launcher.launch(Manifest.permission.RECORD_AUDIO)
                wait.await()
            }
        }
    }
}

@Composable
internal actual fun rememberCameraCapture(onResult: (PlatformFile?) -> Unit): (() -> Unit)? {
    val launcher = rememberCameraPickerLauncher(onResult = onResult)
    return { launcher.launch() }
}

internal actual fun openWithSystem(path: String, displayName: String) {
    runCatching { FileKit.openFileWithDefaultApplication(PlatformFile(path)) }
}

private object StickerImageLoader {
    @Volatile private var loader: ImageLoader? = null

    fun get(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(context.applicationContext)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
            }
            .build()
            .also { loader = it }
    }
}

@Composable
internal actual fun AnimatedSticker(bytes: ByteArray, modifier: Modifier) {
    val context = LocalContext.current
    AsyncImage(
        model = bytes,
        imageLoader = StickerImageLoader.get(context),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

@Composable
internal actual fun rememberFileDropModifier(onHover: (Boolean) -> Unit, onDrop: (List<PlatformFile>) -> Unit): Modifier = Modifier

internal actual val usesDesktopComposer: Boolean = false
