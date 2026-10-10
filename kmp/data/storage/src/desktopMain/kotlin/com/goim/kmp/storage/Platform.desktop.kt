package com.goim.kmp.storage

import com.goim.kmp.common.PlatformContext
import com.russhwolf.settings.PreferencesSettings
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.util.prefs.Preferences

actual val SystemFileSystem: FileSystem = FileSystem.SYSTEM

actual fun goimRootDirectory(context: PlatformContext): Path {
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    return when {
        "mac" in os -> "$home/Library/Application Support/GOIM".toPath()
        "win" in os -> (System.getenv("APPDATA") ?: "$home/AppData/Roaming").toPath() / "GOIM"
        else -> (System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotEmpty() } ?: "$home/.local/share").toPath() / "goim"
    }
}

actual fun createSecureStore(context: PlatformContext): SecureStore =
    SettingsSecureStore(PreferencesSettings(Preferences.userRoot().node("com/goim/kmp/secure")))
