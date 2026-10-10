package com.goim.kmp.di

import com.goim.kmp.common.LogBuffer
import com.goim.kmp.common.PlatformContext
import com.goim.kmp.domain.AuthRepository
import com.goim.kmp.domain.LoginUseCase
import com.goim.kmp.domain.ServerSettingsRepository
import com.goim.kmp.network.GoImApi
import com.goim.kmp.network.createHttpClient
import com.goim.kmp.repository.AuthRepositoryImpl
import com.goim.kmp.repository.ServerSettingsRepositoryImpl
import com.goim.kmp.repository.SessionManager
import com.goim.kmp.storage.AppDirectories
import com.goim.kmp.storage.DevicePreferencesStore
import com.goim.kmp.storage.SessionStore
import com.goim.kmp.storage.createSecureStore
import com.goim.kmp.storage.goimRootDirectory
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

/** 设备级单例；用户级对象由 [SessionManager] 按会话创建与销毁。 */
fun appModule(context: PlatformContext) = module {
    single { context }
    single { AppDirectories(goimRootDirectory(get())) }
    single { DevicePreferencesStore(get<AppDirectories>().deviceJson) }
    single { createSecureStore(get()) }
    single { SessionStore(get(), get()) }
    single { createHttpClient() }
    single<ServerSettingsRepository> { ServerSettingsRepositoryImpl(get()) }
    single {
        val settings = get<ServerSettingsRepository>()
        GoImApi(get()) { settings.apiBaseUrl.value }
    }
    single<AuthRepository> { AuthRepositoryImpl(get(), get()) }
    single { LoginUseCase(get()) }
    single { SessionManager(get(), get(), get(), get(), get(), get()) }
}

fun initKoin(context: PlatformContext) {
    if (KoinPlatform.getKoinOrNull() != null) return
    LogBuffer.install()
    startKoin { modules(appModule(context)) }
}
