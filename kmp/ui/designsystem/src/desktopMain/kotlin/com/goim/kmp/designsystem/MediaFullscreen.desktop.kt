package com.goim.kmp.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

actual fun mediaFullscreenDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false)

@Composable
actual fun MediaFullscreenWindowEffect() = Unit
