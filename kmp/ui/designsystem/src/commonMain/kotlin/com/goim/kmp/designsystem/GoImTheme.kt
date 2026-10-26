package com.goim.kmp.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 聊天专属色，全部取自 M3 色彩角色，随动态取色 / 深色模式变化。 */
@Immutable
data class GoImChatColors(
    val outgoingBubble: Color,
    val incomingBubble: Color,
    val outgoingText: Color,
    val incomingText: Color,
)

/** 布局断点与组件级尺寸。 */
object GoImDimens {
    val mediumBreakpoint = 600.dp
    val expandedBreakpoint = 840.dp
    val listPaneWidth = 360.dp
    val formMaxWidth = 420.dp

    /** 宽屏表单弹窗（添加好友、创建 / 加入群聊）的宽度与最大高度。 */
    val dialogWidth = 480.dp
    val dialogMaxHeight = 640.dp

    /** 应用内文件预览（宽屏弹窗、PDF 页面）的最大宽度。 */
    val filePreviewMaxWidth = 960.dp

    /** 单字段表单弹窗（加入群聊）的高度。 */
    val compactDialogHeight = 400.dp

    /** 聊天右侧信息面板的宽度；聊天栏不足 [chatWithPanelMinWidth] 时面板浮在聊天之上。 */
    val sidePanelWidth = 360.dp
    val chatWithPanelMinWidth = 720.dp

    /** 输入框、搜索框、消息输入框（单行）与表单按钮的统一高度。 */
    val inputHeight = 40.dp

    /** 列表底部给 FAB 让出的空间：FAB 56 + 上下边距 16 × 2。 */
    val fabClearance = 88.dp

    const val BUBBLE_MAX_WIDTH_RATIO = 0.72f
    val listAvatarSize = GoImSize.avatarLg
    val messageAvatarSize = GoImSize.avatarMd
    val bubblePaddingHorizontal = GoImSpacing.s4
    val bubblePaddingVertical = GoImSpacing.s2

    /** 单行文字气泡的高度（bodyLarge 行高 24 + 上下内边距），语音等单行气泡与之对齐。 */
    val bubbleMinHeight = 40.dp

    /** 消息气泡：radius-lg，靠近发送方一侧的尾角收窄为 radius-xs。 */
    val outgoingBubbleShape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    val incomingBubbleShape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
}

// Material Theme Builder，seed #415F91
private val LightColors = lightColorScheme(
    primary = Color(0xFF415F91),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF284777),
    secondary = Color(0xFF565F71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF3E4759),
    tertiary = Color(0xFF705575),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFAD8FD),
    onTertiaryContainer = Color(0xFF573E5C),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2E3036),
    inverseOnSurface = Color(0xFFF0F0F7),
    inversePrimary = Color(0xFFAAC7FF),
    surfaceDim = Color(0xFFD9D9E0),
    surfaceBright = Color(0xFFF9F9FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE2E2E9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAAC7FF),
    onPrimary = Color(0xFF0A305F),
    primaryContainer = Color(0xFF284777),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFDDBCE0),
    onTertiary = Color(0xFF3F2844),
    tertiaryContainer = Color(0xFF573E5C),
    onTertiaryContainer = Color(0xFFFAD8FD),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2E3036),
    inversePrimary = Color(0xFF415F91),
    surfaceDim = Color(0xFF111318),
    surfaceBright = Color(0xFF37393E),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
)

private val GoImShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private fun ColorScheme.chatColors() = GoImChatColors(
    outgoingBubble = primary,
    incomingBubble = surfaceContainerHighest,
    outgoingText = onPrimary,
    incomingText = onSurface,
)

private val LocalChatColors = staticCompositionLocalOf { LightColors.chatColors() }

object GoImTheme {
    val chatColors: GoImChatColors
        @Composable get() = LocalChatColors.current
}

/** Android 12+ 返回壁纸取色方案；其他平台返回 null。 */
@Composable
internal expect fun dynamicColorScheme(darkTheme: Boolean): ColorScheme?

@Composable
fun GoImTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dynamic = if (dynamicColor) dynamicColorScheme(darkTheme) else null
    val colorScheme = dynamic ?: if (darkTheme) DarkColors else LightColors
    val chatColors = remember(colorScheme) { colorScheme.chatColors() }
    CompositionLocalProvider(LocalChatColors provides chatColors) {
        MaterialTheme(colorScheme = colorScheme, typography = GoImTypography, shapes = GoImShapes, content = content)
    }
}
