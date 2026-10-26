package com.goim.kmp.common

actual typealias PlatformContext = android.content.Context

actual val platformDescription: String
    get() = "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MODEL}"

actual val devicePlatform: String = "android"
