package com.goim.kmp.feature.contacts

import com.goim.kmp.domain.FriendRepository
import com.goim.kmp.model.Friend
import com.goim.kmp.model.FriendRequest
import com.goim.kmp.model.FriendRequestStatus
import com.goim.kmp.model.GroupMember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MemberInfoTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeFriends : FriendRepository {
        val friends = MutableStateFlow<List<Friend>>(emptyList())
        val history = MutableStateFlow<List<FriendRequest>>(emptyList())
        val sent = mutableListOf<String>()
        override fun observeFriends(): Flow<List<Friend>> = friends
        override fun observeRequests(): Flow<List<FriendRequest>> = history
        override fun observeRequestHistory(): Flow<List<FriendRequest>> = history
        override suspend fun refresh() = Unit
        override suspend fun sendRequest(to: String) {
            sent += to
            history.value = history.value + FriendRequest("me", to)
        }
        override suspend fun respond(fromUid: String, accept: Boolean) = Unit
        override suspend fun remove(uid: String) = Unit
        override suspend fun setRemark(uid: String, remark: String) = Unit
    }

    @Test
    fun memberLabelPrefersGroupAliasThenFriendRemark() {
        val state = GroupInfoUiState(remarks = mapOf("bob" to "老王", "carol" to "同事"))

        assertEquals("小王", state.labelOf(GroupMember("g", "bob", alias = "小王")))
        assertEquals("同事", state.labelOf(GroupMember("g", "carol")))
        assertEquals("", state.labelOf(GroupMember("g", "dave")))
    }

    @Test
    fun strangerCanBeAddedAndThenShowsPending() = runTest(dispatcher) {
        val friends = FakeFriends()
        val vm = FriendInfoViewModel("bob", "Bob", friends, selfUid = "me")
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(PendingRequest.NONE, vm.state.value.pending)
        assertFalse(vm.isSelf)

        vm.addFriend()
        advanceUntilIdle()

        assertEquals(listOf("bob"), friends.sent)
        assertEquals(PendingRequest.SENT, vm.state.value.pending)
    }

    @Test
    fun incomingRequestAndSelfAreRecognized() = runTest(dispatcher) {
        val friends = FakeFriends()
        friends.history.value = listOf(
            FriendRequest("bob", "me"),
            FriendRequest("me", "carol", status = FriendRequestStatus.REJECTED),
        )
        val bob = FriendInfoViewModel("bob", "Bob", friends, selfUid = "me")
        val carol = FriendInfoViewModel("carol", "Carol", friends, selfUid = "me")
        backgroundScope.launch { bob.state.collect {} }
        backgroundScope.launch { carol.state.collect {} }
        advanceUntilIdle()

        assertEquals(PendingRequest.RECEIVED, bob.state.value.pending)
        assertEquals(PendingRequest.NONE, carol.state.value.pending)
        assertTrue(FriendInfoViewModel("me", "Me", friends, selfUid = "me").isSelf)
    }
}
