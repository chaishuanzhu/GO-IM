package com.goim.kmp.storage

import com.goim.kmp.common.PlatformContext
import com.goim.kmp.model.User
import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 凭据存储：Android EncryptedSharedPreferences、iOS Keychain、Desktop Java Preferences（降级，未加密）。 */
interface SecureStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

expect fun createSecureStore(context: PlatformContext): SecureStore

internal class SettingsSecureStore(private val settings: Settings) : SecureStore {
    override fun get(key: String): String? = settings.getStringOrNull(key)
    override fun set(key: String, value: String) = settings.putString(key, value)
    override fun remove(key: String) = settings.remove(key)
}

class InMemorySecureStore : SecureStore {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String) = map[key]
    override fun set(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

/**
 * 会话凭据：`session.{uid}` → `{username, token}`，活跃 UID 记在 device.json（与 iOS `KeychainStore` 同模型）。
 * 多账号：凭据保存过的 UID 按最近使用排在 `accounts` 里，切换账号不需要重新登录。
 */
class SessionStore(
    private val secure: SecureStore,
    private val device: DevicePreferencesStore,
) {
    fun save(user: User) {
        secure.set(key(user.uid), json.encodeToString(Payload.serializer(), Payload(user.username, user.token)))
        activate(user.uid)
    }

    /** 已保存凭据的账号，最近使用在前；凭据已失效的条目会被跳过。 */
    fun accounts(): List<User> {
        val prefs = device.load()
        // M4 之前登录的账号只记在 activeUID 里
        return (listOfNotNull(prefs.activeUID) + prefs.accounts.orEmpty()).distinct().mapNotNull(::load)
    }

    /** 设为活跃账号并移到列表最前。 */
    fun activate(uid: String) {
        device.update { it.copy(activeUID = uid, accounts = listOf(uid) + it.accounts.orEmpty().filter { u -> u != uid }) }
    }

    /** 回到登录页添加账号：清活跃 UID，保留所有凭据。 */
    fun deactivate() {
        device.update { it.copy(activeUID = null) }
    }

    /** 删除某账号的凭据（不删本地数据）。 */
    fun forget(uid: String) {
        secure.remove(key(uid))
        device.update {
            it.copy(
                activeUID = it.activeUID.takeIf { active -> active != uid },
                accounts = it.accounts.orEmpty().filter { u -> u != uid },
            )
        }
    }

    fun load(uid: String): User? {
        val raw = secure.get(key(uid)) ?: return null
        val payload = runCatching { json.decodeFromString(Payload.serializer(), raw) }.getOrNull() ?: return null
        if (payload.token.isEmpty()) return null
        return User(uid = uid, username = payload.username.ifEmpty { uid }, token = payload.token)
    }

    fun loadActive(): User? = device.load().activeUID?.takeIf { it.isNotEmpty() }?.let(::load)

    fun clearActive() {
        device.load().activeUID?.let(::forget) ?: deactivate()
    }

    private fun key(uid: String) = "session.${UserHome.sanitizeUid(uid)}"

    @Serializable
    private data class Payload(val username: String, val token: String)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
