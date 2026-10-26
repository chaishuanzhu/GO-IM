package com.goim.kmp.feature.settings

import com.goim.kmp.common.DomainError
import com.goim.kmp.model.AdminChatPage
import com.goim.kmp.model.AdminConversation
import com.goim.kmp.model.AdminCreatedUser
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
        val disabled = mutableListOf<Pair<String, Boolean>>()
        var failDisable = false
        val created = mutableListOf<String>()
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
            createUser = { email ->
                when (email) {
                    "taken@x.com" -> throw DomainError.Server(409, "email already registered")
                    else -> {
                        calls.created += email
                        val password = if (email.startsWith("down")) "Pw23" else null
                        AdminCreatedUser(AdminUser("new", "n", email, false, false, 0), password)
                    }
                }
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
    fun deleteUserRemovesRowAndAdjustsCount() = runTest(dispatcher) {
        val calls = Calls()
        val vm = vm(calls)
        vm.ensureUsers()

        vm.deleteUser(user(1))
        assertTrue(calls.deletedUsers.isEmpty())

        vm.deleteUser(user(2))
        val s = vm.state.value
        assertEquals(listOf("u2"), calls.deletedUsers)
        assertTrue(s.users.items.none { it.uid == "u2" })
        assertEquals(59, s.usersTotal)
        assertEquals(2, s.stats?.totalUsers)
        assertNotNull(s.notice)
    }

    @Test
    fun createUserPrependsRowAndHandlesMailFailure() = runTest(dispatcher) {
        val calls = Calls()
        val vm = vm(calls)
        vm.ensureUsers()

        vm.createUser("ok@x.com")
        var s = vm.state.value
        assertEquals("new", s.users.items.first().uid)
        assertEquals(61, s.usersTotal)
        assertEquals(4, s.stats?.totalUsers)
        assertNull(s.unsentPassword)
        assertEquals("已添加 ok@x.com，初始密码已发送到该邮箱", s.notice)
        assertFalse(s.creatingUser)

        vm.createUser("down@x.com")
        s = vm.state.value
        assertEquals("Pw23", s.unsentPassword?.initialPassword)
        vm.dismissUnsentPassword()
        assertNull(vm.state.value.unsentPassword)

        vm.createUser("taken@x.com")
        assertEquals("添加失败：该邮箱已被注册", vm.state.value.notice)
        assertEquals(listOf("ok@x.com", "down@x.com"), calls.created)
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
    fun chatLocatesFocusedMessage() = runTest(dispatcher) {
        val all = (1000L downTo 1L).map(::message)
        val cursors = mutableListOf<Long?>()
        fun chat(focus: AdminChatFocus) = AdminChatViewModel(
            load = { before, limit ->
                cursors += before
                AdminChatPage(all.filter { before == null || it.timestampMs < before }.take(limit), emptyMap(), title = "Bob")
            },
            deleteMessage = {},
            focus = focus,
        )

        val vm = chat(AdminChatFocus(750, 750))
        assertEquals(listOf(null, 901L, 801L), cursors)
        assertEquals(750L, vm.state.value.highlightId)
        assertEquals("Bob", vm.state.value.title)
        assertNull(vm.state.value.notice)
        vm.clearHighlight()
        assertNull(vm.state.value.highlightId)

        val gone = chat(AdminChatFocus(9999, 950))
        assertNull(gone.state.value.highlightId)
        assertEquals("未找到该消息，可能已被删除", gone.state.value.notice)

        cursors.clear()
        val tooOld = chat(AdminChatFocus(-1, -1))
        assertEquals(11, cursors.size)
        assertTrue(tooOld.state.value.endReached)
        assertEquals("未找到该消息，可能已被删除", tooOld.state.value.notice)
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
                createUser = { error("unused") },
                deleteUser = {},
                setUserDisabled = { _, _ -> },
                messages = { _, _ -> emptyList() },
            ),
        )
        vm.ensureUsers()
        val page = vm.state.value.users
        assertEquals("服务端未启用该功能", page.error)
        assertTrue(page.items.isEmpty() && !page.loading)
    }
}
