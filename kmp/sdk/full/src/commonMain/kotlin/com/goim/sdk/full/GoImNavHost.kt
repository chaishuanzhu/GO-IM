package com.goim.sdk.full

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goim.kmp.designsystem.GoImTheme
import com.goim.kmp.designsystem.GoImUiSlots
import com.goim.kmp.designsystem.LocalGoImUiSlots
import com.goim.kmp.model.Appearance
import com.goim.kmp.navigation.MainNavHost
import com.goim.kmp.repository.SessionState
import com.goim.kmp.repository.UserSession
import com.goim.sdk.lite.GoImClient
import com.goim.sdk.lite.GoImSdkPreview

/** Full SDK 可选行为：是否展示设置入口等（预留；当前主区与示范 App 一致）。 */
data class GoImUiOptions(
    val showSettings: Boolean = true,
)

/**
 * Full SDK 主入口：在已 [GoImClient.openSession] / [GoImClient.restore] 后嵌入默认 CMP 主界面。
 * 通过 [slots] 替换会话行、气泡、顶栏等；无会话时显示加载指示（宿主应先完成身份注入）。
 */
@GoImSdkPreview
@Composable
fun GoImNavHost(
    client: GoImClient,
    modifier: Modifier = Modifier,
    slots: GoImUiSlots = GoImUiSlots(),
    options: GoImUiOptions = GoImUiOptions(),
    onExit: (() -> Unit)? = null,
) {
    @Suppress("UNUSED_VARIABLE")
    val unusedOptions = options
    val state by client.sessionManager.state.collectAsStateWithLifecycle()
    when (val current = state) {
        is SessionState.LoggedIn -> {
            GoImSessionNavHost(
                session = current.session,
                modifier = modifier,
                slots = slots,
                onExit = onExit,
            )
        }
        SessionState.LoggedOut -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            if (onExit != null) {
                LaunchedEffect(Unit) { /* 宿主可监听 session 自行处理退出 */ }
            }
        }
    }
}

/**
 * 直接挂载已有 [UserSession]（示范 App 在登录后走此路径，避免再包一层 [GoImClient]）。
 */
@GoImSdkPreview
@Composable
fun GoImSessionNavHost(
    session: UserSession,
    modifier: Modifier = Modifier,
    slots: GoImUiSlots = GoImUiSlots(),
    appearance: Appearance? = null,
    onExit: (() -> Unit)? = null,
) {
    @Suppress("UNUSED_VARIABLE")
    val unusedExit = onExit
    val darkTheme = when (appearance) {
        Appearance.LIGHT -> false
        Appearance.DARK -> true
        Appearance.SYSTEM, null -> isSystemInDarkTheme()
    }
    val providedSlots = remember(slots) { slots }
    CompositionLocalProvider(LocalGoImUiSlots provides providedSlots) {
        Box(modifier.fillMaxSize()) {
            // 主题由外层宿主控制时跳过二次包裹；此处仅在显式传入 appearance 时套 GoImTheme
            if (appearance != null) {
                GoImTheme(darkTheme = darkTheme) {
                    MainNavHost(session)
                }
            } else {
                MainNavHost(session)
            }
        }
    }
}
