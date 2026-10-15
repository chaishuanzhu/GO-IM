package com.goim.kmp.repository

import com.goim.kmp.domain.AuthRepository
import com.goim.kmp.domain.ServerSettingsRepository
import com.goim.kmp.model.Appearance
import com.goim.kmp.model.EmailCodePurpose
import com.goim.kmp.model.TransportKind
import com.goim.kmp.model.User
import com.goim.kmp.model.UserProfile
import com.goim.kmp.network.GoImApi
import com.goim.kmp.storage.DevicePreferencesStore
import com.goim.kmp.storage.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AuthRepositoryImpl(
    private val api: GoImApi,
    private val sessions: SessionStore,
) : AuthRepository {
    override suspend fun login(account: String, password: String): User = api.login(account, password)

    override suspend fun register(email: String, code: String, password: String): User = api.register(email, code, password)

    override suspend fun sendEmailCode(email: String, purpose: EmailCodePurpose) = api.sendEmailCode(email, purpose)

    override suspend fun resetPassword(email: String, code: String, newPassword: String) =
        api.resetPassword(email, code, newPassword)

    override fun updateSavedProfile(profile: UserProfile) =
        sessions.updateProfile(profile.uid, profile.nickname, profile.avatarUrl, profile.email.ifEmpty { null })

    override fun currentUser(): User? = sessions.loadActive()

    override suspend fun saveSession(user: User) = sessions.save(user)

    override suspend fun logout() = sessions.clearActive()

    override fun savedAccounts(): List<User> = sessions.accounts()

    override fun activate(uid: String): User? = sessions.load(uid)?.also { sessions.activate(uid) }

    override fun deactivate() = sessions.deactivate()

    override fun forget(uid: String) = sessions.forget(uid)
}

class ServerSettingsRepositoryImpl(private val device: DevicePreferencesStore) : ServerSettingsRepository {
    private val baseUrl = MutableStateFlow(device.load().apiBaseURL?.takeIf { it.isNotBlank() } ?: DEFAULT_API_BASE_URL)
    private val transport = MutableStateFlow(TransportKind.fromRaw(device.load().preferredTransport))

    override val apiBaseUrl: StateFlow<String> = baseUrl.asStateFlow()
    override val preferredTransport: StateFlow<TransportKind> = transport.asStateFlow()

    private val appearanceState = MutableStateFlow(Appearance.fromRaw(device.load().appearance))
    override val appearance: StateFlow<Appearance> = appearanceState.asStateFlow()

    override fun setAppearance(appearance: Appearance) {
        appearanceState.value = appearance
        device.update { it.copy(appearance = appearance.raw) }
    }

    init {
        if (device.load().apiBaseURL.isNullOrBlank()) device.update { it.copy(apiBaseURL = baseUrl.value) }
    }

    override fun setApiBaseUrl(url: String) {
        val normalized = url.trim().trimEnd('/')
        baseUrl.value = normalized
        device.update { it.copy(apiBaseURL = normalized) }
    }

    override fun setPreferredTransport(kind: TransportKind) {
        transport.value = kind
        device.update { it.copy(preferredTransport = kind.raw) }
    }

    companion object {
        const val DEFAULT_API_BASE_URL = "https://im.chaisz.com"
    }
}
