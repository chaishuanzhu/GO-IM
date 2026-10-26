package com.goim.kmp.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * `GOIM/Shared/device.json`，前三个键名与 iOS `DevicePreferences` 相同。
 * [accounts]（最近使用在前）、[appearance]（`system` / `light` / `dark`）与 [deviceId]（多端登录的设备标识）为 KMP 扩展。
 */
@Serializable
data class DevicePreferences(
    val activeUID: String? = null,
    val apiBaseURL: String? = null,
    val preferredTransport: String? = null,
    val accounts: List<String>? = null,
    val appearance: String? = null,
    val deviceId: String? = null,
)

/** 内存缓存为准，修改后整份写回（tmp + atomicMove）。 */
class DevicePreferencesStore(
    private val file: Path,
    private val fs: FileSystem = SystemFileSystem,
) {
    private val state = MutableStateFlow(read())

    val prefs: StateFlow<DevicePreferences> = state.asStateFlow()

    fun load(): DevicePreferences = state.value

    fun update(mutate: (DevicePreferences) -> DevicePreferences): DevicePreferences {
        val next = state.updateAndGet(mutate)
        runCatching { write(state.value) }
        return next
    }

    private fun read(): DevicePreferences {
        if (!fs.exists(file)) return DevicePreferences()
        return runCatching {
            json.decodeFromString(DevicePreferences.serializer(), fs.read(file) { readUtf8() })
        }.getOrDefault(DevicePreferences())
    }

    private fun write(prefs: DevicePreferences) {
        val dir = file.parent ?: return
        fs.createDirectories(dir)
        val tmp = dir / "${file.name}.tmp"
        fs.write(tmp) { writeUtf8(json.encodeToString(DevicePreferences.serializer(), prefs)) }
        fs.atomicMove(tmp, file)
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
