package com.goim.kmp.common

actual abstract class PlatformContext private constructor() {
    companion object INSTANCE : PlatformContext()
}

actual val platformDescription: String
    get() = "${System.getProperty("os.name")} ${System.getProperty("os.version")} · JVM ${System.getProperty("java.version")}"
