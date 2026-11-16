package com.goim.kmp.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/**
 * 图片预览 / 头像裁剪等全出血媒体层的 Dialog 属性。
 * Android：内容铺进系统栏；iOS：不套平台 insets。
 */
expect fun mediaFullscreenDialogProperties(): DialogProperties

/** 打开媒体全屏层时把状态栏 / 导航栏图标改为浅色（配 [GoImMediaColors.background]）。 */
@Composable
expect fun MediaFullscreenWindowEffect()
