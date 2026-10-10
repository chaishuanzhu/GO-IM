package com.goim.kmp.storage

import com.goim.kmp.model.User
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageTest {

    @Test
    fun sanitizeUid() {
        assertEquals("alice", UserHome.sanitizeUid("alice"))
        assertEquals("a_b_c", UserHome.sanitizeUid("a/b c"))
        assertEquals("unknown", UserHome.sanitizeUid(""))
    }

    @Test
    fun layout() {
        val dirs = AppDirectories("/root/GOIM".toPath())
        assertEquals("/root/GOIM/Shared/device.json", dirs.deviceJson.toString())
        assertEquals("/root/GOIM/Users/a_b/db/goim.sqlite", dirs.userHome("a/b").databaseFile.toString())
    }

    @Test
    fun devicePreferencesPersistAndUseIosKeys() {
        val fs = FakeFileSystem()
        val file = "/GOIM/Shared/device.json".toPath()
        DevicePreferencesStore(file, fs).update { it.copy(apiBaseURL = "http://x:8080", preferredTransport = "tcp") }

        val text = fs.read(file) { readUtf8() }
        assertTrue("\"apiBaseURL\":\"http://x:8080\"" in text)
        assertTrue("activeUID" !in text)
        assertEquals("tcp", DevicePreferencesStore(file, fs).load().preferredTransport)
    }

    @Test
    fun sessionRoundTrip() {
        val fs = FakeFileSystem()
        val device = DevicePreferencesStore("/GOIM/Shared/device.json".toPath(), fs)
        val sessions = SessionStore(InMemorySecureStore(), device)

        assertNull(sessions.loadActive())
        sessions.save(User("alice", "", "tok"))
        assertEquals(User("alice", "alice", "tok"), sessions.loadActive())
        assertEquals("alice", device.load().activeUID)

        sessions.clearActive()
        assertNull(sessions.loadActive())
        assertNull(sessions.load("alice"))
    }

    @Test
    fun multipleAccountsKeepCredentialsAndRecency() {
        val fs = FakeFileSystem()
        val device = DevicePreferencesStore("/GOIM/Shared/device.json".toPath(), fs)
        val sessions = SessionStore(InMemorySecureStore(), device)

        sessions.save(User("alice", "Alice", "t1"))
        sessions.save(User("bob", "Bob", "t2"))
        assertEquals(listOf("bob", "alice"), sessions.accounts().map { it.uid })

        sessions.activate("alice")
        assertEquals("alice", sessions.loadActive()?.uid)
        assertEquals(listOf("alice", "bob"), sessions.accounts().map { it.uid })

        sessions.deactivate()
        assertNull(sessions.loadActive())
        assertEquals(2, sessions.accounts().size, "添加账号不清凭据")

        sessions.activate("bob")
        sessions.clearActive()
        assertEquals(listOf("alice"), sessions.accounts().map { it.uid })
        assertNull(device.load().activeUID)

        sessions.forget("alice")
        assertTrue(sessions.accounts().isEmpty())
        assertTrue(device.load().accounts.orEmpty().isEmpty())
    }

    @Test
    fun accountsIncludeLegacyActiveUid() {
        val fs = FakeFileSystem()
        val device = DevicePreferencesStore("/GOIM/Shared/device.json".toPath(), fs)
        val secure = InMemorySecureStore()
        SessionStore(secure, device).save(User("alice", "Alice", "t1"))
        device.update { it.copy(accounts = null) }
        assertEquals(listOf("alice"), SessionStore(secure, device).accounts().map { it.uid })
    }
}
