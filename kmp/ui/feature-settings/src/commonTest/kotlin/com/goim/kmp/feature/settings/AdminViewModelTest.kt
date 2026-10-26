package com.goim.kmp.feature.settings

import com.goim.kmp.common.DomainError
import com.goim.kmp.model.AdminChatPage
import com.goim.kmp.model.AdminConversation
import com.goim.kmp.model.AdminMessage
import com.goim.kmp.model.AdminSender
import com.goim.kmp.model.AdminStats
import com.goim.kmp.model.AdminUser
import com.goim.kmp.model.AdminUserPage
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.MsgType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
class AdminViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val stats = AdminStats(true, 1, 3, 120, mapOf("redis" to "ok"), 10, 20)
    private fun user(i: Int) = AdminUser("u$i", "User $i", "", false, false, i.toLong())
    private fun message(ts: Long) = AdminMessage(ts, "a", "b", ChatType.SINGLE, MsgType.Text, "m$ts", ts, false)

    private class Calls {
        val userOffsets = mutableListOf<Int>()
        val messageCursors = mutableListOf<Long?>()
        val deletedUsers = mutableListOf<String>()
        val deletedMessages = mutableListOf<Long>()
        val disabled = mutableListOf<Pair<String, Boolean>>()
        var failDisable = false
    }

    private fun vm(
        calls: Calls = Calls(),
        allUsers: List<AdminUser> = (1..60).map(::user),
        allMessages: List<AdminMessage> = (200L downTo 141L).map(::message),
        failStats: Boolean = false,
    ) = AdminViewModel(
        AdminDeps(
            selfUid = "u1",
            stats = { if (failStats) throw DomainError.Server(403, "admin access required") else stats },
            users = { offset, limit ->
                calls.userOffsets += offset
                AdminUserPage(allUsers.drop(offset).take(limit), allUsers.size)
            },
            deleteUser = { calls.deletedUsers += it },
            setUserDisabled = { uid, disabled ->
                if (calls.failDisable) throw DomainError.Server(500, "internal error")
                calls.disabled += uid to disabled
            },
            messages = { before, limit ->
                calls.messageCursors += before
                allMessages.filter { before == null || it.timestampMs < before }.take(limit)
            },
            deleteMessage = { calls.deletedMessages += it },
        ),
    )

    @Test
    fun statsLoadOnStartAndMapForbidden() = runTest(dispatcher) {
        assertEquals(stats, vm().state.value.stats)
        val failed = vm(failStats = true).state.value
        assertNull(failed.stats)
        assertEquals("需要管理员权限（角色变更后请重新登录）", failed.statsError)
    }

    @Test
    fun usersPageUntilTotal() = runTest(dispatcher) {
        val calls = Calls()
        val vm = vm(calls)
        vm.ensureUsers()
        vm.ensureUsers()
        assertEquals(50, vm.state.value.users.items.size)
        assertEquals(60, vm.state.value.usersTotal)
        vm.loadMoreUsers()
        assertEquals(60, vm.state.value.users.items.size)
        assertTrue(vm.state.value.users.endReached)
        vm.loadMoreUsers()
        assertEquals(listOf(0, 50), calls.userOffsets)
    }

    @Test
    fun messagesPageByTimestampCursor() = runTest(dispatcher) {
        val calls = Calls()
        val vm = vm(calls)
        vm.ensureMessages()
        vm.loadMoreMessages()
        vm.loadMoreMessages()
        assertEquals(listOf(null, 151L), calls.messageCursors)
        assertEquals(60, vm.state.value.messages.items.size)
        assertTrue(vm.state.value.messages.endReached)
    }

    @Test
    fun deleteRemovesRowsAndAdjustsCounts() = runTest(dispatcher) {
        val calls = Calls()
        val vm = vm(calls)
        vm.ensureUsers()
        vm.ensureMessages()

        vm.deleteUser(user(1))
        assertTrue(calls.deletedUsers.isEmpty())

        vm.deleteUser(user(2))
        vm.deleteMessage(message(200))
        val s = vm.state.value
        assertEquals(listOf("u2"), calls.deletedUsers)
        assertEquals(listOf(200L), calls.deletedMessages)
        assertTrue(s.users.items.none { it.uid == "u2" })
        assertEquals(59, s.usersTotal)
        assertEquals(2, s.stats?.totalUsers)
        assertEquals(119, s.stats?.totalMessages)
        assertTrue(s.messages.items.none { it.msgId == 200L })
        assertNotNull(s.notice)
    }

    @Test
    fun disableUpdatesRowAndSkipsSelf() = runTest(dispatcher) {
        val calls = Calls()
        val vm = vm(calls)
        vm.ensureUsers()

        vm.setUserDisabled(user(1), true)
        assertTrue(calls.disabled.isEmpty())

        vm.setUserDisabled(user(2), true)
        assertEquals(listOf("u2" to true), calls.disabled)
        assertTrue(vm.state.value.users.items.first { it.uid == "u2" }.isDisabled)
        assertNull(vm.state.value.updatingUser)

        vm.setUserDisabled(user(2), false)
        assertFalse(vm.state.value.users.items.first { it.uid == "u2" }.isDisabled)

        calls.failDisable = true
        vm.consumeNotice()
        vm.setUserDisabled(user(3), true)
        assertFalse(vm.state.value.users.items.first { it.uid == "u3" }.isDisabled)
        assertTrue(vm.state.value.notice!!.startsWith("操作失败"))
        assertNull(vm.state.value.updatingUser)
    }

    @Test
    fun chatPagesOlderAndDeletes() = runTest(dispatcher) {
        val all = (200L downTo 141L).map(::message)
        val cursors = mutableListOf<Long?>()
        val deleted = mutableListOf<Long>()
        val vm = AdminChatViewModel(
            load = { before, limit ->
                cursors += before
                val page = all.filter { before == null || it.timestampMs < before }.take(limit)
                AdminChatPage(page, mapOf("a" to AdminSender("Alice", "")))
            },
            deleteMessage = { deleted += it },
        )
        assertEquals(50, vm.state.value.messages.size)
        assertEquals("Alice", vm.state.value.senders["a"]?.nickname)
        vm.loadOlder()
        vm.loadOlder()
        assertEquals(listOf(null, 151L), cursors)
        assertEquals(60, vm.state.value.messages.size)
        assertTrue(vm.state.value.endReached)

        vm.delete(message(200))
        assertEquals(listOf(200L), deleted)
        assertTrue(vm.state.value.messages.none { it.msgId == 200L })
    }

    @Test
    fun conversationsLoadAndFail() = runTest(dispatcher) {
        val conv = AdminConversation("b", ChatType.SINGLE, "", 3, message(1))
        assertEquals(listOf(conv), AdminConversationsViewModel { listOf(conv) }.state.value.items)
        val failed = AdminConversationsViewModel { throw DomainError.Server(503, "message store not available") }.state.value
        assertEquals("服务端未启用该功能", failed.error)
    }

    @Test
    fun firstPageFailureShowsErrorState() = runTest(dispatcher) {
        val vm = AdminViewModel(
            AdminDeps(
                selfUid = "u1",
                stats = { stats },
                users = { _, _ -> throw DomainError.Server(503, "user store not available") },
                deleteUser = {},
                setUserDisabled = { _, _ -> },
                messages = { _, _ -> emptyList() },
                deleteMessage = {},
            ),
        )
        vm.ensureUsers()
        val page = vm.state.value.users
        assertEquals("服务端未启用该功能", page.error)
        assertTrue(page.items.isEmpty() && !page.loading)
    }
}
