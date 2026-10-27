package com.goim.kmp.feature.contacts

import com.goim.kmp.domain.FriendRepository
import com.goim.kmp.domain.ProfileRepository
import com.goim.kmp.model.Friend
import com.goim.kmp.model.FriendRequest
import com.goim.kmp.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AddFriendViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeFriends : FriendRepository {
        val sent = mutableListOf<String>()
        var failFor: String? = null
        override fun observeFriends(): Flow<List<Friend>> = flowOf(emptyList())
        override fun observeRequests(): Flow<List<FriendRequest>> = flowOf(emptyList())
        override suspend fun refresh() = Unit
        override suspend fun sendRequest(to: String) {
            if (to == failFor) throw IllegalStateException("对方已向你发送好友请求")
            sent += to
        }
        override suspend fun respond(fromUid: String, accept: Boolean) = Unit
        override suspend fun remove(uid: String) = Unit
        override suspend fun setRemark(uid: String, remark: String) = Unit
    }

    private class FakeProfiles(private val users: List<UserProfile>) : ProfileRepository {
        val queries = mutableListOf<String>()
        override val me = MutableStateFlow(UserProfile("100000", "me"))
        override fun observe(uid: String): Flow<UserProfile?> = flowOf(null)
        override fun prefetch(uids: Collection<String>) = Unit
        override suspend fun refreshMe() = me.value
        override suspend fun updateNickname(nickname: String) = me.value
        override suspend fun updateSignature(signature: String) = me.value
        override suspend fun updateAvatar(bytes: ByteArray, fileName: String, mime: String) = me.value
        override suspend fun search(query: String): List<UserProfile> {
            queries += query
            return users.filter { query in it.nickname || it.uid == query }
        }
        override suspend fun sendBindEmailCode(email: String) = Unit
        override suspend fun bindEmail(email: String, code: String) = me.value
    }

    @Test
    fun searchAndMarkSentOnlyAfterSuccess() = runTest(dispatcher) {
        val friends = FakeFriends().apply { failFor = "100002" }
        val profiles = FakeProfiles(listOf(UserProfile("100001", "alice"), UserProfile("100002", "alina")))
        val vm = AddFriendViewModel(friends, profiles)

        vm.search()
        advanceUntilIdle()
        assertEquals(emptyList(), profiles.queries)

        vm.onQueryChange("  al ")
        vm.search()
        advanceUntilIdle()
        assertEquals(listOf("al"), profiles.queries)
        assertEquals(listOf("100001", "100002"), vm.state.value.results.map { it.uid })
        assertTrue(vm.state.value.searched)

        vm.sendRequest("100001")
        assertEquals(setOf("100001"), vm.state.value.sending)
        advanceUntilIdle()
        assertEquals(listOf("100001"), friends.sent)
        assertEquals(setOf("100001"), vm.state.value.sent)
        assertEquals(emptySet(), vm.state.value.sending)
        assertEquals("好友请求已发送", vm.message.value)

        vm.sendRequest("100002")
        advanceUntilIdle()
        assertEquals(setOf("100001"), vm.state.value.sent)
        assertNotNull(vm.message.value)
        assertTrue(vm.message.value != "好友请求已发送")
    }
}
