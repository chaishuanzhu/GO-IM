package com.goim.kmp.feature.settings

import co.touchlab.kermit.Severity
import com.goim.kmp.common.LogEntry
import com.goim.kmp.domain.ServerSettingsRepository
import com.goim.kmp.model.Appearance
import com.goim.kmp.model.ConnectionState
import com.goim.kmp.model.TransportKind
import com.goim.kmp.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelsTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeSettings : ServerSettingsRepository {
        override val apiBaseUrl = MutableStateFlow("http://h")
        override val preferredTransport = MutableStateFlow(TransportKind.WEB_SOCKET)
        override val appearance = MutableStateFlow(Appearance.SYSTEM)
        override fun setApiBaseUrl(url: String) { apiBaseUrl.value = url }
        override fun setPreferredTransport(kind: TransportKind) { preferredTransport.value = kind }
        override fun setAppearance(appearance: Appearance) { this.appearance.value = appearance }
    }

    private val alice = User("alice", "Alice", "t1")
    private val bob = User("bob", "Bob", "t2")

    @Test
    fun accountsAppearanceAndSwitchFailure() = runTest(dispatcher) {
        val settings = FakeSettings()
        val accounts = MutableStateFlow(listOf(alice, bob))
        val switched = mutableListOf<String>()
        var added = false
        val forgotten = mutableListOf<String>()
        val vm = SettingsViewModel(
            user = alice,
            settings = settings,
            connectionState = MutableStateFlow(ConnectionState.CONNECTED),
            switchTransport = {},
            accountActions = AccountActions(
                accounts = accounts,
                switchTo = { uid -> switched += uid; uid != "bob" },
                addAccount = { added = true },
                forget = { forgotten += it },
            ),
        )
        backgroundScope.launch { vm.state.collect {} }

        assertTrue(vm.supportsAccounts)
        assertEquals(listOf("alice", "bob"), vm.state.value.accounts.map { it.uid })

        vm.setAppearance(Appearance.DARK)
        assertEquals(Appearance.DARK, vm.state.value.appearance)

        vm.switchAccount("alice")
        assertTrue(switched.isEmpty(), "切到当前账号是空操作")

        vm.switchAccount("bob")
        advanceUntilIdle()
        assertEquals(listOf("bob"), switched)
        assertEquals("该账号登录信息已失效，请重新登录", vm.state.value.error)
        vm.consumeError()
        assertNull(vm.state.value.error)

        vm.addAccount()
        vm.forgetAccount("bob")
        advanceUntilIdle()
        assertTrue(added)
        assertEquals(listOf("bob"), forgotten)
    }

    @Test
    fun serverSettingsPageHasNoAccounts() {
        val vm = SettingsViewModel(null, FakeSettings(), null, switchTransport = {})
        assertFalse(vm.supportsAccounts)
    }

    @Test
    fun developerLogFiltersAndExportCleanup() = runTest(dispatcher) {
        val logs = MutableStateFlow(
            listOf(
                LogEntry(1, 0, Severity.Debug, "HTTP", "→ GET /unread"),
                LogEntry(2, 0, Severity.Warn, "IM", "heartbeat lost"),
                LogEntry(3, 0, Severity.Info, "Session", "session mounted uid=alice"),
            ),
        )
        val discarded = mutableListOf<String>()
        val vm = DeveloperViewModel(
            DeveloperDeps(
                logs = logs,
                clearLogs = { logs.value = emptyList() },
                connectionState = MutableStateFlow(ConnectionState.CONNECTED),
                reconnect = {},
                info = { listOf("账号" to "alice") },
                databaseStats = { listOf("messages" to 3L) },
                storageUsage = { listOf("media" to 10L) },
                exportDatabase = { "/tmp/x.sqlite" },
                discardExport = { discarded += it },
                clearImageCache = {},
            ),
        )
        backgroundScope.launch { vm.logs.collect {} }
        backgroundScope.launch { vm.networkLogs.collect {} }
        advanceUntilIdle()

        assertEquals(listOf(3L, 2L, 1L), vm.logs.value.map { it.id }, "最新在前")
        vm.setLevelFilter(LogLevelFilter.WARN)
        assertEquals(listOf(2L), vm.logs.value.map { it.id })

        assertEquals(listOf(2L, 1L), vm.networkLogs.value.map { it.id })
        vm.setNetworkFilter(NetworkFilter.HTTP)
        assertEquals(listOf(1L), vm.networkLogs.value.map { it.id })
        vm.setNetworkFilter(NetworkFilter.ALL)
        vm.setQuery("heartbeat")
        assertEquals(listOf(2L), vm.networkLogs.value.map { it.id })

        assertEquals(listOf("messages" to 3L), vm.state.value.tableCounts)

        vm.exportDatabase { false }
        advanceUntilIdle()
        vm.exportDatabase { error("disk full") }
        advanceUntilIdle()
        assertEquals(listOf("/tmp/x.sqlite", "/tmp/x.sqlite"), discarded, "取消或失败都删除临时快照")
        assertTrue(vm.state.value.notice!!.startsWith("导出失败"))
        assertFalse(vm.state.value.busy)

        vm.clearLogs()
        vm.setQuery("")
        vm.setLevelFilter(LogLevelFilter.ALL)
        assertTrue(vm.logs.value.isEmpty())
    }
}
