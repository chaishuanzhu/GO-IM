package com.goim.kmp.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.goim.kmp.common.DomainError
import com.goim.kmp.database.AppDatabase
import com.goim.kmp.domain.ConnectionRepository
import com.goim.kmp.domain.InboundEvent
import com.goim.kmp.domain.OutboundEnvelope
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.ConnectionState
import com.goim.kmp.model.ConversationId
import com.goim.kmp.model.FriendRequestStatus
import com.goim.kmp.model.Message
import com.goim.kmp.model.MessageStatus
import com.goim.kmp.model.MsgType
import com.goim.kmp.model.TransportKind
import com.goim.kmp.model.User
import com.goim.kmp.network.GoImUserApi
import com.goim.kmp.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SocialFlowTest {
    private val me = User("alice", "alice", "jwt")
    private val db = Room.inMemoryDatabaseBuilder<AppDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val httpCalls = mutableListOf<String>()

    /** 路径 → (状态码, 响应体)。 */
    private var routes: Map<String, Pair<HttpStatusCode, String>> = emptyMap()

    private val api = GoImUserApi(
        createHttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                httpCalls += path
                val (status, body) = routes[path] ?: (HttpStatusCode.NotFound to "no route")
                respond(body, status, jsonHeaders)
            },
        ),
        { "http://host" },
        me,
    )

    private class FakeConnection(connected: Boolean) : ConnectionRepository {
        override val state = MutableStateFlow(if (connected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED)
        val events = Channel<InboundEvent>(Channel.UNLIMITED)
        override val inboundEvents: Flow<InboundEvent> = events.receiveAsFlow()
        val sent = mutableListOf<OutboundEnvelope>()
        var onSend: (OutboundEnvelope) -> Unit = {}

        override suspend fun connect(user: User, transport: TransportKind) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun ensureConnected() = Unit
        override suspend fun send(envelope: OutboundEnvelope) {
            sent += envelope
            onSend(envelope)
        }
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun eventually(check: suspend () -> Boolean) = withTimeout(5_000) {
        while (!check()) yield()
    }

    @Test
    fun friendRequestOverSocketUsesGatewayEcho() = runBlocking<Unit> {
        val connection = FakeConnection(connected = true)
        val friends = FriendRepositoryImpl(api, db, connection, me.uid, confirmTimeoutMillis = 2_000)
        connection.onSend = { friends.onRequestResult(InboundEvent.FriendRequestResult("bob", error = null)) }

        friends.sendRequest("bob")

        assertEquals(listOf<OutboundEnvelope>(OutboundEnvelope.FriendRequest("bob")), connection.sent)
        assertTrue(httpCalls.isEmpty(), "回执已确认，不应再走 HTTP")
    }

    @Test
    fun friendRequestEchoErrorSurfaces() = runBlocking<Unit> {
        val connection = FakeConnection(connected = true)
        val friends = FriendRepositoryImpl(api, db, connection, me.uid, confirmTimeoutMillis = 2_000)
        connection.onSend = { friends.onRequestResult(InboundEvent.FriendRequestResult("bob", error = "already friends")) }

        val error = assertFailsWith<DomainError.Server> { friends.sendRequest("bob") }
        assertEquals("already friends", error.body)
    }

    @Test
    fun friendRequestWithoutEchoVerifiesOverHttp() = runBlocking<Unit> {
        val connection = FakeConnection(connected = true)
        val friends = FriendRepositoryImpl(api, db, connection, me.uid, confirmTimeoutMillis = 100)

        routes = mapOf("/friend/request" to (HttpStatusCode.Conflict to "request already pending"))
        friends.sendRequest("bob") // 409：请求已存在，视为成功

        routes = mapOf("/friend/request" to (HttpStatusCode.ServiceUnavailable to "friend system not available"))
        val error = assertFailsWith<DomainError.Server> { friends.sendRequest("bob") }
        assertEquals(503, error.code)
    }

    @Test
    fun offlineFriendRequestGoesOverHttpAndRejectsSelf() = runBlocking<Unit> {
        val friends = FriendRepositoryImpl(api, db, FakeConnection(connected = false), me.uid)
        routes = mapOf("/friend/request" to (HttpStatusCode.OK to """{"status":"ok"}"""))

        friends.sendRequest(" bob ")
        assertEquals(listOf("/friend/request"), httpCalls)
        assertFailsWith<DomainError.InvalidState> { friends.sendRequest("alice") }
    }

    @Test
    fun inboundFriendEventsUpdatePendingList() = runBlocking<Unit> {
        val connection = FakeConnection(connected = true)
        val friends = FriendRepositoryImpl(api, db, connection, me.uid, pollIntervalMillis = 10)
        val messages = MessageRepositoryImpl(db, connection, this)
        val conversations = ConversationRepositoryImpl(db)
        val pumps = CoroutineScope(coroutineContext + Job())
        SessionCoordinator(me, connection, messages, conversations, pumps, friends = friends) {}.start()

        // 刷新失败（无好友服务）也要先落库，角标即时出现
        routes = mapOf("/friend/list" to (HttpStatusCode.ServiceUnavailable to "friend system not available"))
        connection.events.send(InboundEvent.FriendRequestReceived("bob", "bob", 10))
        eventually { friends.observeRequests().first().map { it.fromUid } == listOf("bob") }

        // 通过长连接接受：服务端处理后刷新结果不再包含该请求
        routes = mapOf(
            "/friend/list" to (
                HttpStatusCode.OK to
                    """{"friends":[{"uid":"bob","friend_uid":"alice"}],"pending_requests":[]}"""
                ),
        )
        friends.respond("bob", accept = true)
        assertEquals(OutboundEnvelope.FriendResponse("bob", true), connection.sent.last())
        assertTrue("/friend/accept" !in httpCalls)
        assertTrue(friends.observeRequests().first().isEmpty())
        assertEquals(listOf("bob"), friends.observeFriends().first().map { it.uid })
        pumps.cancel()
    }

    @Test
    fun requestHistoryKeepsHandledAndSentRecords() = runBlocking<Unit> {
        val friends = FriendRepositoryImpl(api, db, FakeConnection(connected = false), me.uid)
        routes = mapOf(
            "/friend/list" to (
                HttpStatusCode.OK to
                    """{"friends":[],"pending_requests":[{"from_uid":"bob","created_at":9}],"request_history":[
                       {"from_uid":"bob","to_uid":"alice","status":0,"created_at":9},
                       {"from_uid":"alice","to_uid":"carol","status":1,"created_at":5}]}"""
                ),
            "/friend/reject" to (HttpStatusCode.OK to """{"status":"ok"}"""),
            "/friend/request" to (HttpStatusCode.OK to """{"status":"ok"}"""),
        )
        friends.refresh()
        assertEquals(listOf("bob"), friends.observeRequests().first().map { it.fromUid })
        assertEquals(2, friends.observeRequestHistory().first().size)

        // 拒绝后从待处理移除，但记录保留并标为已拒绝；新发出的请求也记一条
        routes = routes - "/friend/list"
        friends.respond("bob", accept = false)
        friends.sendRequest("dave")
        assertTrue(friends.observeRequests().first().isEmpty())
        val history = friends.observeRequestHistory().first().associateBy { it.peerUid(me.uid) }
        assertEquals(setOf("bob", "carol", "dave"), history.keys)
        assertEquals(FriendRequestStatus.REJECTED, history.getValue("bob").status)
        assertEquals(FriendRequestStatus.ACCEPTED, history.getValue("carol").status)
        assertEquals(FriendRequestStatus.PENDING, history.getValue("dave").status)
    }

    @Test
    fun acceptOverSocketWhileRefreshFailsStillAddsFriend() = runBlocking<Unit> {
        val connection = FakeConnection(connected = true)
        val friends = FriendRepositoryImpl(api, db, connection, me.uid, confirmTimeoutMillis = 50, pollIntervalMillis = 10)
        friends.onRequestReceived(InboundEvent.FriendRequestReceived("bob", "bob", 10))

        // 长连接已接受，但 HTTP 不可用：刷新失败，回退的 /friend/accept 报 404（请求已被处理）
        routes = mapOf(
            "/friend/list" to (HttpStatusCode.BadGateway to "upstream down"),
            "/friend/accept" to (HttpStatusCode.NotFound to "no pending friend request from bob to alice"),
        )
        friends.respond("bob", accept = true)

        assertTrue(friends.observeRequests().first().isEmpty())
        assertEquals(listOf("bob"), friends.observeFriends().first().map { it.uid })
    }

    @Test
    fun acceptedResponseAddsFriendEvenIfRefreshFails() = runBlocking<Unit> {
        val connection = FakeConnection(connected = true)
        val friends = FriendRepositoryImpl(api, db, connection, me.uid)
        val messages = MessageRepositoryImpl(db, connection, this)
        val conversations = ConversationRepositoryImpl(db)
        val pumps = CoroutineScope(coroutineContext + Job())
        SessionCoordinator(me, connection, messages, conversations, pumps, friends = friends) {}.start()

        routes = mapOf("/friend/list" to (HttpStatusCode.BadGateway to "upstream down"))
        connection.events.send(InboundEvent.FriendResponded("bob", accepted = true))
        eventually { friends.observeFriends().first().map { it.uid } == listOf("bob") }

        connection.events.send(InboundEvent.FriendResponded("carol", accepted = false))
        connection.events.send(InboundEvent.FriendResponded("dave", accepted = true))
        eventually { friends.observeFriends().first().map { it.uid } == listOf("bob", "dave") }
        pumps.cancel()
    }

    @Test
    fun removeFriendDeletesLocallyAndToleratesAlreadyRemoved() = runBlocking<Unit> {
        val friends = FriendRepositoryImpl(api, db, FakeConnection(connected = true), me.uid)
        routes = mapOf(
            "/friend/list" to (
                HttpStatusCode.OK to
                    """{"friends":[{"uid":"bob","friend_uid":"alice"},{"uid":"carol","friend_uid":"alice"}],"pending_requests":[]}"""
                ),
        )
        friends.refresh()

        routes = mapOf(
            "/friend/remove" to (HttpStatusCode.OK to """{"status":"ok"}"""),
            "/friend/list" to (HttpStatusCode.BadGateway to "upstream down"),
        )
        friends.remove("bob")
        assertEquals(listOf("carol"), friends.observeFriends().first().map { it.uid })

        // 对方已先删除：服务端 404，本地照常移除
        routes = mapOf("/friend/remove" to (HttpStatusCode.NotFound to "not friends"))
        friends.remove("carol")
        assertTrue(friends.observeFriends().first().isEmpty())

        routes = mapOf("/friend/remove" to (HttpStatusCode.ServiceUnavailable to "friend system not available"))
        assertFailsWith<DomainError.Server> { friends.remove("dave") }
    }

    @Test
    fun removedByPeerDropsFriend() = runBlocking<Unit> {
        val connection = FakeConnection(connected = true)
        val friends = FriendRepositoryImpl(api, db, connection, me.uid)
        val messages = MessageRepositoryImpl(db, connection, this)
        val conversations = ConversationRepositoryImpl(db)
        val pumps = CoroutineScope(coroutineContext + Job())
        SessionCoordinator(me, connection, messages, conversations, pumps, friends = friends) {}.start()

        routes = mapOf("/friend/list" to (HttpStatusCode.BadGateway to "upstream down"))
        connection.events.send(InboundEvent.FriendResponded("bob", accepted = true))
        eventually { friends.observeFriends().first().map { it.uid } == listOf("bob") }

        connection.events.send(InboundEvent.FriendResponded("bob", accepted = false, removed = true))
        eventually { friends.observeFriends().first().isEmpty() }
        pumps.cancel()
    }

    @Test
    fun groupRefreshSyncsTitlesAndDropsLeftGroups() = runBlocking<Unit> {
        val conversations = ConversationRepositoryImpl(db)
        val groups = GroupRepositoryImpl(api, db, conversations)
        routes = mapOf(
            "/group/create" to (HttpStatusCode.OK to """{"group_id":"g_1","name":"Dev","owner_uid":"alice","members":["alice","bob"]}"""),
            "/group/list" to (HttpStatusCode.OK to """{"groups":[{"id":"g_1","name":"Dev Team","owner_uid":"alice","member_count":2}]}"""),
            "/group/leave" to (HttpStatusCode.OK to """{"ok":"true"}"""),
        )

        val created = groups.create("Dev", listOf("bob"))
        assertEquals("g_1", created.groupId)
        assertEquals("Dev", conversations.conversation(ConversationId.group("g_1"))?.title)

        groups.refresh()
        assertEquals("Dev Team", conversations.conversation(ConversationId.group("g_1"))?.title)
        assertEquals(listOf("Dev Team"), groups.observeGroups().first().map { it.name })

        groups.leave("g_1")
        assertTrue(groups.observeGroups().first().isEmpty())
        // 退群保留会话与聊天记录（同 iOS）
        assertEquals("Dev Team", conversations.conversation(ConversationId.group("g_1"))?.title)
    }

    @Test
    fun unknownGroupMessageTriggersGroupRefresh() = runBlocking<Unit> {
        val connection = FakeConnection(connected = true)
        val messages = MessageRepositoryImpl(db, connection, this)
        val conversations = ConversationRepositoryImpl(db)
        val groups = GroupRepositoryImpl(api, db, conversations)
        val pumps = CoroutineScope(coroutineContext + Job())
        SessionCoordinator(me, connection, messages, conversations, pumps, groups = groups) {}.start()
        routes = mapOf(
            "/group/list" to (HttpStatusCode.OK to """{"groups":[{"id":"g_7","name":"Ops","owner_uid":"bob","member_count":3}]}"""),
        )

        connection.events.send(
            InboundEvent.MessageReceived(
                Message(
                    serverMsgId = 1, clientSeq = 1, conversationId = ConversationId.group("g_7"), fromUid = "bob",
                    toUid = "g_7", chatType = ChatType.GROUP, msgType = MsgType.Text,
                    content = """{"type":"member_joined","group_id":"g_7","uid":"alice"}""",
                    timestampMs = 1, status = MessageStatus.SENT, isOutgoing = false,
                ),
            ),
        )

        eventually { conversations.conversation(ConversationId.group("g_7"))?.title == "Ops" }
        assertEquals("alice 加入了群聊", conversations.conversation(ConversationId.group("g_7"))?.lastMessagePreview)
        pumps.cancel()
    }

    @Test
    fun searchFallsBackToLocalWhenServerUnavailable() = runBlocking<Unit> {
        val connection = FakeConnection(connected = false)
        val messages = MessageRepositoryImpl(db, connection, this)
        messages.upsert(
            Message(
                serverMsgId = 5, clientSeq = 5, conversationId = ConversationId.dm("alice", "bob"), fromUid = "bob",
                toUid = "alice", chatType = ChatType.SINGLE, msgType = MsgType.Text, content = "hello world",
                timestampMs = 5, status = MessageStatus.SENT, isOutgoing = false,
            ),
        )
        val search = SearchRepositoryImpl(api, db, me.uid)
        routes = mapOf("/search" to (HttpStatusCode.InternalServerError to "search failed"))

        val result = search.search("world")

        assertTrue(result.isLocalFallback)
        val hit = result.hits.single()
        assertEquals("bob", hit.peerOrGroupId)
        assertEquals(5L, hit.serverMsgId)

        // 网关无消息库：200 + messages=null，同样回退本机
        routes = mapOf("/search" to (HttpStatusCode.OK to """{"messages":null,"total":0}"""))
        assertTrue(search.search("world").isLocalFallback)
        assertTrue(search.search("nothing-here").hits.isEmpty())

        routes = mapOf("/search" to (HttpStatusCode.BadRequest to "q (query) required"))
        assertFailsWith<DomainError.Server> { search.search("world") }
    }
}
