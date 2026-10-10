package com.goim.kmp

import androidx.compose.ui.window.ComposeUIViewController
import com.goim.kmp.common.PlatformContext
import com.goim.kmp.di.initKoin
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    initKoin(PlatformContext.INSTANCE)
    return ComposeUIViewController { App() }
}
