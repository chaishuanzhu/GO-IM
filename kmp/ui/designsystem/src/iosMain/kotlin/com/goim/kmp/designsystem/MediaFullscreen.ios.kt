package com.goim.kmp.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalComposeUiApi::class)
actual fun mediaFullscreenDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false, usePlatformInsets = false)

@Composable
actual fun MediaFullscreenWindowEffect() = Unit
