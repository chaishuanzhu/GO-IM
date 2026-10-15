package com.goim.kmp.feature.settings

import com.goim.kmp.domain.ProfileRepository
import com.goim.kmp.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
class ProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeProfiles : ProfileRepository {
        override val me = MutableStateFlow(UserProfile("100001", "alice"))
        val avatars = mutableListOf<Pair<String, String>>()
        var bindFails = false

        override fun observe(uid: String): Flow<UserProfile?> = flowOf(null)
        override fun prefetch(uids: Collection<String>) = Unit
        override suspend fun refreshMe() = me.value
        override suspend fun updateNickname(nickname: String): UserProfile {
            require(nickname.isNotBlank()) { "nickname required" }
            return me.value.copy(nickname = nickname).also { me.value = it }
        }
        override suspend fun updateAvatar(bytes: ByteArray, fileName: String, mime: String): UserProfile {
            avatars += fileName to mime
            return me.value.copy(avatarUrl = "/avatar/1").also { me.value = it }
        }
        override suspend fun search(query: String): List<UserProfile> = emptyList()
        override suspend fun sendBindEmailCode(email: String) = Unit
        override suspend fun bindEmail(email: String, code: String): UserProfile {
            if (bindFails) error("invalid or expired code")
            return me.value.copy(email = email).also { me.value = it }
        }
    }

    @Test
    fun nicknameAndAvatar() = runTest(dispatcher) {
        val profiles = FakeProfiles()
        val vm = ProfileViewModel(profiles)
        vm.updateNickname("Alice W")
        advanceUntilIdle()
        assertEquals("Alice W", vm.profile.value.nickname)
        assertEquals("昵称已更新", vm.message.value)
        vm.consumeMessage()

        vm.updateNickname(" ")
        advanceUntilIdle()
        assertNotNull(vm.message.value)
        assertEquals("Alice W", vm.profile.value.nickname)

        vm.updateAvatar(byteArrayOf(1), "me.PNG")
        advanceUntilIdle()
        assertEquals("me.PNG" to "image/png", profiles.avatars.single())
        assertEquals("/avatar/1", vm.profile.value.avatarUrl)
        assertFalse(vm.busy.value)
    }

    @Test
    fun bindEmailFlow() = runTest(dispatcher) {
        val profiles = FakeProfiles().apply { bindFails = true }
        val vm = ProfileViewModel(profiles, cooldownSeconds = 2)
        vm.sendBindCode("a@example.com")
        runCurrent()
        assertTrue(vm.bind.value.codeSent)
        assertEquals(2, vm.bind.value.cooldown)

        var done = false
        vm.bindEmail("a@example.com", "123456") { done = true }
        runCurrent()
        assertFalse(done)
        assertNotNull(vm.bind.value.error)

        profiles.bindFails = false
        vm.bindEmail("a@example.com", "123456") { done = true }
        runCurrent()
        assertTrue(done)
        assertEquals("a@example.com", vm.profile.value.email)
        assertEquals("邮箱已绑定", vm.message.value)

        advanceTimeBy(2_100)
        vm.resetBind()
        assertEquals(0, vm.bind.value.cooldown)
        assertNull(vm.bind.value.error)
    }

    @Test
    fun imageMimeFromName() {
        assertEquals("image/jpeg", ProfileViewModel.imageMime("x.jpg"))
        assertEquals("image/webp", ProfileViewModel.imageMime("x.WEBP"))
        assertEquals("image/jpeg", ProfileViewModel.imageMime("noext"))
    }
}
