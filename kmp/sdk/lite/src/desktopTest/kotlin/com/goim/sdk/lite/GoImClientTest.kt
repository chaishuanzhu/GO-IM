@file:OptIn(GoImSdkPreview::class)

package com.goim.sdk.lite

import com.goim.kmp.common.PlatformContext
import com.goim.kmp.model.TransportKind
import com.goim.kmp.repository.SessionState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GoImClientTest {
    @Test
    fun openAndCloseSession() = runBlocking {
        val client = GoImClient.create(
            GoImConfig(
                serverUrl = "http://127.0.0.1:1",
                transport = TransportKind.WEB_SOCKET,
                deviceId = "sdk-test-device",
            ),
            PlatformContext.INSTANCE,
        )
        assertNull(client.session.value)

        val session = client.openSession(GoImIdentity(uid = "u1", token = "tok", nickname = "N"))
        assertEquals("u1", session.user.uid)
        assertEquals("tok", session.user.token)
        assertNotNull(client.session.value)
        assertEquals("u1", client.sessionManager.currentSession!!.user.uid)

        client.closeSession()
        assertNull(client.session.value)
        assertEquals(SessionState.LoggedOut, client.sessionManager.state.value)
    }
}
