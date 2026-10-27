package com.goim.kmp.feature.settings

import com.goim.kmp.domain.ProfileRepository
import com.goim.kmp.model.UserProfile
import com.goim.kmp.platform.EncodedImage
import androidx.compose.ui.geometry.Offset
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

    }

    @Test
    fun avatarIsCroppedBeforeUpload() = runTest(dispatcher) {
        val profiles = FakeProfiles()
        val crops = mutableListOf<CropSquare>()
        val vm = ProfileViewModel(
            profiles,
            imageDispatcher = dispatcher,
            normalize = { bytes -> if (bytes.isEmpty()) null else EncodedImage(bytes, 400, 300) },
            crop = { _, square -> crops += square; byteArrayOf(9) },
        )

        vm.pickAvatar(ByteArray(0))
        advanceUntilIdle()
        assertNull(vm.cropSource.value)
        assertEquals("无法读取这张图片", vm.message.value)

        vm.pickAvatar(byteArrayOf(1))
        advanceUntilIdle()
        assertEquals(400, vm.cropSource.value?.width)

        vm.cancelCrop()
        assertNull(vm.cropSource.value)
        assertTrue(profiles.avatars.isEmpty())

        vm.pickAvatar(byteArrayOf(1))
        advanceUntilIdle()
        vm.confirmCrop(CropSquare(50, 0, 300))
        advanceUntilIdle()
        assertNull(vm.cropSource.value)
        assertEquals(listOf(CropSquare(50, 0, 300)), crops)
        assertEquals("avatar.jpg" to "image/jpeg", profiles.avatars.single())
        assertEquals("头像已更新", vm.message.value)
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
    fun cropStateCoversFrameAndMapsToImagePixels() {
        // 400×300 的图，取景框 150px：缩放 1 时短边 300 → 150，即 0.5 倍
        val crop = AvatarCropState(400, 300, 150f)
        assertEquals(CropSquare(50, 0, 300), crop.result())

        // 拖不出边界：水平最多移动 (400×0.5 − 150) / 2 = 25px
        crop.transform(Offset(1_000f, 1_000f), 1f)
        assertEquals(Offset(25f, 0f), crop.offset)
        assertEquals(CropSquare(0, 0, 300), crop.result())

        // 放大 2 倍：取景框对应原图 150px
        crop.transform(Offset.Zero, 2f)
        assertEquals(150, crop.result().size)
        crop.transform(Offset.Zero, 100f)
        assertEquals(AvatarCropState.MAX_ZOOM, crop.zoom)
        crop.transform(Offset.Zero, 0.01f)
        assertEquals(1f, crop.zoom)
    }
}
