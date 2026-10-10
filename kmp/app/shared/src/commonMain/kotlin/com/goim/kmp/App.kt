package com.goim.kmp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import androidx.compose.foundation.isSystemInDarkTheme
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import com.goim.kmp.domain.ServerSettingsRepository
import com.goim.kmp.model.Appearance
import okio.FileSystem
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.goim.kmp.common.PlatformContext
import com.goim.kmp.designsystem.GoImTheme
import com.goim.kmp.navigation.AuthNavHost
import com.goim.kmp.navigation.MainNavHost
import com.goim.kmp.platform.networkAvailability
import com.goim.kmp.repository.SessionEndReason
import com.goim.kmp.repository.SessionManager
import com.goim.kmp.repository.SessionState
import com.goim.kmp.repository.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * 桌面端透明标题栏的高度。默认所有页面都让出这段高度；主页双栏布局 [claimed] 后自行处理，
 * 让面板顶到窗口顶部，只给左侧导航栏让位。
 */
class TitleBarInset(val height: Dp) {
    var claimed by mutableStateOf(false)
}

val LocalTitleBarInset = staticCompositionLocalOf<TitleBarInset?> { null }

/**
 * 三端共用的根 Composable；调用前需 `initKoin`。
 * [titleBar]：macOS 桌面端透明标题栏下的拖动区（铺在内容之下，空白处可拖动窗口），高度 [titleBarHeight]。
 */
@Composable
fun App(titleBarHeight: Dp = 0.dp, titleBar: @Composable () -> Unit = {}) {
    val titleBarInset = remember(titleBarHeight) { TitleBarInset(titleBarHeight).takeIf { titleBarHeight > 0.dp } }
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            // 聊天图多为缩略图：内存缓存限制在可用内存的 15%，磁盘缓存 256MB（URL 含 token，按账号天然隔离）
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "goim_image_cache")
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }
    val settings = koinInject<ServerSettingsRepository>()
    val appearance by settings.appearance.collectAsStateWithLifecycle()
    val darkTheme = when (appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
    GoImTheme(darkTheme = darkTheme) {
        val manager = koinInject<SessionManager>()
        val state by manager.state.collectAsStateWithLifecycle()
        var restored by remember { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        val imageLoader = SingletonImageLoader.get(LocalPlatformContext.current)

        // 换号 / 登出：清图片内存缓存（对齐 iOS 清 Kingfisher）
        val activeUid = (state as? SessionState.LoggedIn)?.session?.user?.uid
        LaunchedEffect(activeUid) { imageLoader.memoryCache?.clear() }

        LaunchedEffect(manager) {
            withContext(Dispatchers.Default) {
                runCatching { manager.restore() }.onFailure { Logger.w(it) { "restore session failed" } }
            }
            restored = true
        }
        LaunchedEffect(manager) {
            manager.ended.collect { reason ->
                val text = when (reason) {
                    SessionEndReason.KICKED -> "账号已在其他设备登录"
                    SessionEndReason.AUTH_EXPIRED -> "登录已失效，请重新登录"
                    SessionEndReason.LOGOUT -> return@collect
                }
                snackbar.showSnackbar(text)
            }
        }

        val current = state
        val background = if (current is SessionState.LoggedIn) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
        Box(Modifier.fillMaxSize().background(background)) {
            Box(Modifier.fillMaxWidth().height(titleBarHeight)) { titleBar() }
            val topInset = if (titleBarInset == null || titleBarInset.claimed) 0.dp else titleBarInset.height
            Box(Modifier.fillMaxSize().padding(top = topInset)) {
                when {
                    !restored -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    current is SessionState.LoggedIn -> key(current.session) {
                        ConnectionKeeper(current.session)
                        CompositionLocalProvider(LocalTitleBarInset provides titleBarInset) {
                            MainNavHost(current.session)
                        }
                    }
                    else -> AuthNavHost()
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
            }
        }
    }
}

/** 回到前台、网络恢复时补连（对齐 iOS `scenePhase` / `NWPathMonitor`）。 */
@Composable
private fun ConnectionKeeper(session: UserSession) {
    val scope = rememberCoroutineScope()
    val context = koinInject<PlatformContext>()

    LifecycleResumeEffect(session) {
        scope.launch { runCatching { session.connection.ensureConnected() } }
        onPauseOrDispose { }
    }
    LaunchedEffect(session) {
        var previous: Boolean? = null
        networkAvailability(context).collect { online ->
            if (online && previous == false) runCatching { session.connection.ensureConnected() }
            previous = online
        }
    }
}
