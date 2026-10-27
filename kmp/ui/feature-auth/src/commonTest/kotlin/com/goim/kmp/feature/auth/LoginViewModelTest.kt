package com.goim.kmp.feature.auth

import com.goim.kmp.domain.AuthRepository
import com.goim.kmp.domain.LoginUseCase
import com.goim.kmp.model.EmailCodePurpose
import com.goim.kmp.model.User
import com.goim.kmp.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeAuth : AuthRepository {
        val codes = mutableListOf<Pair<String, EmailCodePurpose>>()
        val resets = mutableListOf<Triple<String, String, String>>()
        val saved = mutableListOf<User>()
        val changes = mutableListOf<Triple<String, String, String>>()
        var failLogin = false
        var mustChange = false
        var registrationOpen = true

        override suspend fun login(account: String, password: String): User {
            if (failLogin) error("invalid account or password")
            return User("100001", "alice", "t", mustChangePassword = mustChange)
        }
        override suspend fun changePassword(user: User, oldPassword: String, newPassword: String) {
            changes += Triple(user.uid, oldPassword, newPassword)
        }
        override suspend fun register(email: String, code: String, password: String) =
            User("100002", email.substringBefore('@'), "t", email = email)
        override suspend fun sendEmailCode(email: String, purpose: EmailCodePurpose) { codes += email to purpose }
        override suspend fun resetPassword(email: String, code: String, newPassword: String) { resets += Triple(email, code, newPassword) }
        override suspend fun registrationOpen() = registrationOpen
        override fun updateSavedProfile(profile: UserProfile) = Unit
        override fun currentUser(): User? = null
        override suspend fun saveSession(user: User) { saved += user }
        override suspend fun logout() = Unit
        override fun savedAccounts(): List<User> = emptyList()
        override fun activate(uid: String): User? = null
        override fun deactivate() = Unit
        override fun forget(uid: String) = Unit
    }

    @Test
    fun emailValidation() {
        assertTrue(LoginUiState.isValidEmail("a@b.co"))
        assertTrue(LoginUiState.isValidEmail(" a.b@mail.example.com "))
        assertFalse(LoginUiState.isValidEmail("a@b"))
        assertFalse(LoginUiState.isValidEmail("@b.com"))
        assertFalse(LoginUiState.isValidEmail("a@@b.com"))
        assertFalse(LoginUiState.isValidEmail("a b@c.com"))
        assertFalse(LoginUiState.isValidEmail("a@b.com."))
    }

    @Test
    fun registerSendsCodeCountsDownAndLogsIn() = runTest(dispatcher) {
        val auth = FakeAuth()
        val loggedIn = mutableListOf<User>()
        val vm = LoginViewModel(LoginUseCase(auth), onLoggedIn = { loggedIn += it }, cooldownSeconds = 3)

        vm.onAccountChange("new@example.com")
        vm.onModeChange(AuthMode.REGISTER)
        assertEquals("new@example.com", vm.state.value.email)
        assertTrue(vm.state.value.canSendCode)

        vm.sendCode()
        runCurrent()
        assertEquals(listOf("new@example.com" to EmailCodePurpose.REGISTER), auth.codes)
        assertEquals(3, vm.state.value.codeCooldown)
        assertFalse(vm.state.value.canSendCode)
        assertNotNull(vm.state.value.info)
        advanceTimeBy(3_100)
        assertEquals(0, vm.state.value.codeCooldown)

        vm.onCodeChange("12a34567")
        assertEquals("123456", vm.state.value.code)
        vm.onPasswordChange("123")
        vm.submit()
        assertEquals("密码至少 6 位", vm.state.value.error)

        vm.onPasswordChange("secret1")
        vm.submit()
        advanceUntilIdle()
        assertEquals("100002", loggedIn.single().uid)
        assertEquals("new", loggedIn.single().nickname)
        assertEquals(loggedIn, auth.saved)
        assertNull(vm.state.value.error)
    }

    @Test
    fun closedRegistrationHidesRegisterMode() = runTest(dispatcher) {
        val auth = FakeAuth()
        val vm = LoginViewModel(LoginUseCase(auth), onLoggedIn = {})
        vm.onModeChange(AuthMode.REGISTER)

        auth.registrationOpen = false
        vm.refreshAuthConfig()
        advanceUntilIdle()
        assertFalse(vm.state.value.registrationOpen)
        assertEquals(AuthMode.LOGIN, vm.state.value.mode)
        vm.onModeChange(AuthMode.REGISTER)
        assertEquals(AuthMode.LOGIN, vm.state.value.mode)
        vm.onModeChange(AuthMode.RESET)
        assertEquals(AuthMode.RESET, vm.state.value.mode)

        auth.registrationOpen = true
        vm.refreshAuthConfig()
        advanceUntilIdle()
        vm.onModeChange(AuthMode.REGISTER)
        assertEquals(AuthMode.REGISTER, vm.state.value.mode)
    }

    @Test
    fun resetPasswordReturnsToLogin() = runTest(dispatcher) {
        val auth = FakeAuth()
        val vm = LoginViewModel(LoginUseCase(auth), onLoggedIn = {}, cooldownSeconds = 1)
        vm.onModeChange(AuthMode.RESET)
        vm.onEmailChange("a@example.com")
        vm.sendCode()
        advanceUntilIdle()
        assertEquals(EmailCodePurpose.RESET_PASSWORD, auth.codes.single().second)

        vm.onCodeChange("654321")
        vm.onPasswordChange("newpass")
        vm.submit()
        advanceUntilIdle()
        assertEquals(Triple("a@example.com", "654321", "newpass"), auth.resets.single())
        val s = vm.state.value
        assertEquals(AuthMode.LOGIN, s.mode)
        assertEquals("a@example.com", s.account)
        assertEquals("", s.password)
        assertNotNull(s.info)
    }

    @Test
    fun mustChangePasswordHoldsSessionUntilChanged() = runTest(dispatcher) {
        val auth = FakeAuth().apply { mustChange = true }
        val loggedIn = mutableListOf<User>()
        val vm = LoginViewModel(LoginUseCase(auth), onLoggedIn = { loggedIn += it })
        vm.onAccountChange("admin@example.com")
        vm.onPasswordChange("Initial123")
        vm.submit()
        advanceUntilIdle()

        assertEquals(AuthMode.CHANGE_PASSWORD, vm.state.value.mode)
        assertTrue(loggedIn.isEmpty())
        assertTrue(auth.saved.isEmpty())

        vm.onPasswordChange("newpass1")
        vm.onConfirmPasswordChange("newpass2")
        vm.submit()
        assertEquals("两次输入的密码不一致", vm.state.value.error)

        vm.onPasswordChange("Initial123")
        vm.onConfirmPasswordChange("Initial123")
        vm.submit()
        assertEquals("新密码不能与初始密码相同", vm.state.value.error)
        assertTrue(auth.changes.isEmpty())

        vm.onPasswordChange("newpass1")
        vm.onConfirmPasswordChange("newpass1")
        vm.submit()
        advanceUntilIdle()
        assertEquals(Triple("100001", "Initial123", "newpass1"), auth.changes.single())
        val user = loggedIn.single()
        assertFalse(user.mustChangePassword)
        assertEquals(listOf(user), auth.saved)
    }

    @Test
    fun leavingForcedChangeDropsPendingLogin() = runTest(dispatcher) {
        val auth = FakeAuth().apply { mustChange = true }
        val vm = LoginViewModel(LoginUseCase(auth), onLoggedIn = {})
        vm.onAccountChange("100001")
        vm.onPasswordChange("Initial123")
        vm.submit()
        advanceUntilIdle()
        vm.onModeChange(AuthMode.LOGIN)

        vm.onModeChange(AuthMode.CHANGE_PASSWORD)
        vm.onPasswordChange("newpass1")
        vm.onConfirmPasswordChange("newpass1")
        vm.submit()
        advanceUntilIdle()
        assertTrue(auth.changes.isEmpty())
        assertNotNull(vm.state.value.error)
    }

    @Test
    fun loginFailureShowsError() = runTest(dispatcher) {
        val auth = FakeAuth().apply { failLogin = true }
        val loggedIn = mutableListOf<User>()
        val vm = LoginViewModel(LoginUseCase(auth), onLoggedIn = { loggedIn += it })
        vm.submit()
        assertEquals("请输入邮箱或用户 ID", vm.state.value.error)

        vm.onAccountChange("100001")
        vm.onPasswordChange("x")
        vm.submit()
        advanceUntilIdle()
        assertTrue(loggedIn.isEmpty())
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.isLoading)
    }
}
