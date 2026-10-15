package com.goim.kmp.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 设计令牌，与 kmp/designtoken.md 一一对应；改值时两边同步。

/** 间距刻度：s{n} = n × 4dp。padding / 间隙 / Spacer 只取这组值。 */
object GoImSpacing {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s8 = 32.dp
    val s10 = 40.dp
    val s12 = 48.dp
    val s16 = 64.dp
    val s20 = 80.dp
    val s24 = 96.dp
}

/** 图标、头像、触控区尺寸。 */
object GoImSize {
    val iconXs = 16.dp
    val iconSm = 20.dp
    val iconMd = 24.dp
    val iconLg = 40.dp
    val iconXl = 64.dp

    val avatarXs = 24.dp
    val avatarSm = 32.dp
    val avatarMd = 40.dp
    val avatarLg = 48.dp
    val avatarXl = 64.dp
    val avatar2xl = 80.dp
    val avatar3xl = 96.dp

    val touchTarget = 48.dp
    val strokeThin = 2.dp
}

/** M3 色调高度（tonal / shadow elevation）。 */
object GoImElevation {
    val level0 = 0.dp
    val level1 = 1.dp
    val level2 = 3.dp
    val level3 = 6.dp
    val level4 = 8.dp
    val level5 = 12.dp
}

/** 图片 / 视频查看器等全出血媒体层，不随主题变化。 */
object GoImMediaColors {
    val background = Color.Black
    val scrim = Color.Black.copy(alpha = 0.45f)
    val content = Color.White
}

private val Display = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold)
private val H1 = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
private val H2 = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
private val H3 = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp)
private val BodyLg = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.15.sp)
private val Body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.25.sp)
private val Label = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp)
private val BodySm = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.4.sp)
private val Caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)

/**
 * 9 级字号映射到 M3 角色。业务代码只用每级的规范角色：
 * displaySmall / headlineSmall / titleLarge / titleMedium / bodyLarge / bodyMedium / labelLarge / bodySmall / labelSmall。
 */
internal val GoImTypography = Typography(
    displayLarge = Display,
    displayMedium = Display,
    displaySmall = Display,
    headlineLarge = H1,
    headlineMedium = H1,
    headlineSmall = H1,
    titleLarge = H2,
    titleMedium = H3,
    titleSmall = Label,
    bodyLarge = BodyLg,
    bodyMedium = Body,
    bodySmall = BodySm,
    labelLarge = Label,
    labelMedium = Caption,
    labelSmall = Caption,
)
