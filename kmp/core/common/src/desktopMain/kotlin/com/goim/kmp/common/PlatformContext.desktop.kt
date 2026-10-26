package com.goim.kmp.common

actual abstract class PlatformContext private constructor() {
    companion object INSTANCE : PlatformContext()
}

actual val platformDescription: String
    get() = "${System.getProperty("os.name")} ${System.getProperty("os.version")} · JVM ${System.getProperty("java.version")}"

actual val devicePlatform: String
    get() = System.getProperty("os.name").orEmpty().lowercase().let {
        when {
            it.startsWith("mac") -> "macos"
            it.startsWith("windows") -> "windows"
            it.contains("linux") -> "linux"
            else -> "desktop"
        }
    }
