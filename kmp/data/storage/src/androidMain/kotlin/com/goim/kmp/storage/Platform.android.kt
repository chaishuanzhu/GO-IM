package com.goim.kmp.storage

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.goim.kmp.common.PlatformContext
import com.russhwolf.settings.SharedPreferencesSettings
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath

actual val SystemFileSystem: FileSystem = FileSystem.SYSTEM

actual fun goimRootDirectory(context: PlatformContext): Path =
    context.filesDir.toOkioPath() / "GOIM"

actual fun createSecureStore(context: PlatformContext): SecureStore {
    val app = context.applicationContext
    val masterKey = MasterKey.Builder(app)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    val prefs = EncryptedSharedPreferences.create(
        app,
        "goim_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    return SettingsSecureStore(SharedPreferencesSettings(prefs))
}
