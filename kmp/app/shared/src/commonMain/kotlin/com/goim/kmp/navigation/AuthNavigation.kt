package com.goim.kmp.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.goim.kmp.domain.LoginUseCase
import com.goim.kmp.domain.ServerSettingsRepository
import com.goim.kmp.feature.auth.LoginScreen
import com.goim.kmp.feature.auth.LoginViewModel
import com.goim.kmp.feature.settings.ServerSettingsScreen
import com.goim.kmp.feature.settings.SettingsViewModel
import com.goim.kmp.repository.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** 未登录：登录 ⇄ 服务器设置；登录页列出本机已保存的账号可直接切换。 */
@Composable
fun AuthNavHost() {
    val nav = rememberNavController()
    val manager = koinInject<SessionManager>()
    val settings = koinInject<ServerSettingsRepository>()
    val login = koinInject<LoginUseCase>()
    val accounts by manager.accounts.collectAsState()
    val scope = rememberCoroutineScope()

    NavHost(nav, startDestination = LoginRoute) {
        composable<LoginRoute> {
            val vm = viewModel {
                LoginViewModel(login, onLoggedIn = { user ->
                    withContext(Dispatchers.Default) { manager.openSession(user) }
                })
            }
            LoginScreen(
                vm,
                onOpenServerSettings = { nav.navigate(ServerSettingsRoute) },
                savedAccounts = accounts,
                onUseSavedAccount = { uid -> scope.launch(Dispatchers.Default) { manager.switchTo(uid) } },
            )
        }
        composable<ServerSettingsRoute> {
            val vm = viewModel {
                SettingsViewModel(
                    user = null,
                    settings = settings,
                    connectionState = null,
                    switchTransport = { settings.setPreferredTransport(it) },
                )
            }
            ServerSettingsScreen(vm, onBack = { nav.popBackStack() })
        }
    }
}
