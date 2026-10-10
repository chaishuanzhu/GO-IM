package com.goim.kmp.repository

import com.goim.kmp.common.PlatformContext
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.Conversation
import com.goim.kmp.model.ConversationId
import com.goim.kmp.model.User
import com.goim.kmp.network.GoImApi
import com.goim.kmp.network.createHttpClient
import com.goim.kmp.storage.AppDirectories
import com.goim.kmp.storage.DevicePreferencesStore
import com.goim.kmp.storage.InMemorySecureStore
import com.goim.kmp.storage.SessionStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 多账号切换：每个账号独立数据库，切换不需要重新登录（无需网关，连接在后台失败即可）。 */
class AccountSwitchTest {
    private val root = Files.createTempDirectory("goim-accounts").toOkioPath()
    private val directories = AppDirectories(root)
    private val device = DevicePreferencesStore(directories.deviceJson, FileSystem.SYSTEM)
    private val settings = ServerSettingsRepositoryImpl(device).also { it.setApiBaseUrl("http://127.0.0.1:1") }
    private val http = createHttpClient()
    private val api = GoImApi(http) { settings.apiBaseUrl.value }
    private val auth = AuthRepositoryImpl(api, SessionStore(InMemorySecureStore(), device))
    private val manager = SessionManager(PlatformContext.INSTANCE, directories, http, api, settings, auth)

    private val alice = User("alice", "Alice", "t1")
    private val bob = User("bob", "Bob", "t2")

    private suspend fun conversationIds(): List<String> =
        manager.currentSession!!.conversations.observeConversations().first().map { it.id }

    @Test
    fun switchAddForgetAndLogout() = runBlocking {
        auth.saveSession(alice)
        manager.mount(alice)
        val aliceChat = Conversation(ConversationId.dm("alice", "carol"), ChatType.SINGLE, "carol", "carol")
        manager.currentSession!!.conversations.ensureConversation(aliceChat)

        auth.saveSession(bob)
        manager.mount(bob)
        assertEquals(listOf("bob", "alice"), manager.accounts.value.map { it.uid })
        assertTrue(conversationIds().isEmpty(), "bob 看不到 alice 的会话")

        assertTrue(manager.switchTo("alice"))
        assertEquals("alice", manager.currentSession!!.user.uid)
        assertEquals(listOf(aliceChat.id), conversationIds())
        assertEquals(listOf("alice", "bob"), manager.accounts.value.map { it.uid })

        manager.addAccount()
        assertIs<SessionState.LoggedOut>(manager.state.value)
        assertNull(auth.currentUser(), "冷启动不会自动进入")
        assertEquals(2, manager.accounts.value.size, "添加账号保留已有凭据")

        assertTrue(manager.switchTo("bob"))
        manager.forget("bob")
        assertEquals(2, manager.accounts.value.size, "不能移除当前账号")
        manager.forget("alice")
        assertEquals(listOf("bob"), manager.accounts.value.map { it.uid })
        assertFalse(manager.switchTo("alice"), "已移除的账号需要重新登录")

        manager.logout()
        assertIs<SessionState.LoggedOut>(manager.state.value)
        assertTrue(manager.accounts.value.isEmpty())
        assertTrue(FileSystem.SYSTEM.exists(directories.userHome("alice").databaseFile), "退出 / 移除不删本地数据")
        FileSystem.SYSTEM.deleteRecursively(root)
    }
}
