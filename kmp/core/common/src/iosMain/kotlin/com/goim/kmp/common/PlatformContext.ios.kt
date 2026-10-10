package com.goim.kmp.common

actual abstract class PlatformContext private constructor() {
    companion object INSTANCE : PlatformContext()
}

actual val platformDescription: String
    get() = platform.UIKit.UIDevice.currentDevice.let { "${it.systemName} ${it.systemVersion} · ${it.model}" }
