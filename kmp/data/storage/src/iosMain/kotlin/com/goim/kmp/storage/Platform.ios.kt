package com.goim.kmp.storage

import com.goim.kmp.common.PlatformContext
import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

actual val SystemFileSystem: FileSystem = FileSystem.SYSTEM

@OptIn(ExperimentalForeignApi::class)
actual fun goimRootDirectory(context: PlatformContext): Path {
    val url = NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    return requireNotNull(url?.path) { "Application Support unavailable" }.toPath() / "GOIM"
}

@OptIn(ExperimentalSettingsImplementation::class)
actual fun createSecureStore(context: PlatformContext): SecureStore =
    SettingsSecureStore(KeychainSettings(service = "com.goim.kmp.keychain"))
