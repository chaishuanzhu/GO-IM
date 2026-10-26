package com.goim.kmp.network

import com.goim.kmp.common.DomainError
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.Group
import com.goim.kmp.model.User
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoImUserApiTest {
    private val me = User("alice", "alice", "jwt")
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): GoImUserApi {
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        return GoImUserApi(createHttpClient(engine), { "http://host:8080" }, me)
    }

    private fun HttpRequestData.form(): String = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    @Test
    fun createGroupPostsCredentialsAndMembers() = runTest {
        val api = api {
            respond(
                """{"group_id":"g_1","name":"Dev","owner_uid":"alice","members":["alice","bob"],"created_at":1}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        val group = api.createGroup("Dev", listOf("bob"))

        assertEquals(Group("g_1", "Dev", "alice", 2), group)
        val req = requests.single()
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("/group/create", req.url.encodedPath)
        val form = req.form()
        assertTrue("uid=alice" in form && "token=jwt" in form && "members=bob" in form && "name=Dev" in form)
    }

    @Test
    fun listGroupsAndMembersUseQueryAuth() = runTest {
        val api = api { req ->
            when (req.url.encodedPath) {
                "/group/list" -> respond(
                    """{"groups":[{"id":"g_1","name":"","owner_uid":"bob","member_count":3,"created_at":1}]}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                else -> respond("""{"group_id":"g_1","members":["bob","alice"]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

        val groups = api.listGroups()
        val members = api.groupMembers("g_1")

        assertEquals(listOf(Group("g_1", "", "bob", 3)), groups)
        assertEquals("g_1", groups.single().displayName)
        assertEquals(listOf("bob", "alice"), members)
        assertEquals("alice", requests[0].url.parameters["uid"])
        assertEquals("jwt", requests[0].url.parameters["token"])
        assertEquals("g_1", requests[1].url.parameters["group_id"])
    }

    @Test
    fun friendListNormalizesPeerAndPending() = runTest {
        val api = api {
            respond(
                """{"uid":"alice","friends":[{"uid":"bob","friend_uid":"alice","status":1,"created_at":1,"nickname":"Bob","avatar_url":"/avatar/5"},
                   {"uid":"","friend_uid":"carol"},{"uid":"alice","friend_uid":"alice"}],
                   "pending_requests":[{"from_uid":"dave","username":"Dave","created_at":9}]}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        val snapshot = api.friendList()

        assertEquals(listOf("bob", "carol"), snapshot.friends.map { it.uid })
        assertEquals("Bob", snapshot.friends[0].displayName)
        assertTrue(snapshot.friends[0].avatarUrl.endsWith("/avatar/5") && snapshot.friends[0].avatarUrl.startsWith("http"))
        assertEquals("carol", snapshot.friends[1].displayName)
        assertEquals("", snapshot.friends[1].avatarUrl)
        val pending = snapshot.pending.single()
        assertEquals("dave", pending.fromUid)
        assertEquals("alice", pending.toUid)
        assertEquals("Dave", pending.displayName)
    }

    @Test
    fun friendListToleratesNullArrays() = runTest {
        val api = api { respond("""{"uid":"alice","friends":null,"pending_requests":null}""", HttpStatusCode.OK, jsonHeaders) }
        val snapshot = api.friendList()
        assertTrue(snapshot.friends.isEmpty() && snapshot.pending.isEmpty())
    }

    @Test
    fun searchMapsConversationForDmAndGroup() = runTest {
        val api = api {
            respond(
                """{"query":"hi","messages":[
                    {"msg_id":101,"cmd":1,"from":"bob","to":"alice","chat_type":1,"content":"hi","timestamp":5},
                    {"msg_id":102,"from":"alice","to":"bob","content":"hi there","timestamp":6},
                    {"msg_id":103,"from":"carol","to":"g_9","chat_type":2,"msg_type":1,"content":"hi all","timestamp":7}
                ],"total":3,"next_cursor":0}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        val hits = api.search("hi", 50)

        assertEquals(listOf(101L, 102L, 103L), hits.map { it.serverMsgId })
        assertEquals("dm:alice_bob", hits[0].conversationId)
        assertEquals("bob", hits[1].peerOrGroupId)
        assertEquals("group:g_9", hits[2].conversationId)
        assertEquals(ChatType.GROUP, hits[2].chatType)
        assertEquals("hi", requests.single().url.parameters["q"])
    }

    @Test
    fun httpErrorCarriesServerText() = runTest {
        val api = api { respond("only the group owner can invite members\n", HttpStatusCode.Forbidden) }
        val error = assertFailsWith<DomainError.Server> { api.inviteToGroup("g_1", "bob") }
        assertEquals(403, error.code)
        assertEquals("only the group owner can invite members", error.body)
    }

    @Test
    fun adminEndpointsParseGatewayJson() = runTest {
        val api = api { req ->
            when (req.url.encodedPath) {
                "/admin/stats" -> respond(
                    """{"status":"degraded","online_users":3,"total_users":10,"total_messages":99,""" +
                        """"dependencies":{"redis":"ok","mysql":"unhealthy: down"},"memory":{"alloc_mb":12,"goroutines":40}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                "/admin/users" -> respond(
                    """{"users":[{"uid":"100001","nickname":"Bob","email":"b@x.com","role":"admin","is_disabled":true,""" +
                        """"created_at":5,"updated_at":0}],"total":7,"offset":0,"limit":50}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                "/admin/messages" -> respond(
                    """{"messages":[{"msg_id":373022222882705408,"seq":1,"cmd":1,"from":"a","to":"g_1","chat_type":2,""" +
                        """"msg_type":1,"content":"{\"recalled\":true}","timestamp":42},{"msg_id":2,"from":"a","to":"b",""" +
                        """"content":"hi","timestamp":41}],"limit":50}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                else -> respond("""{"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

        val stats = api.adminStats()
        assertEquals(false, stats.healthy)
        assertEquals(listOf("mysql", "redis"), stats.dependencies.keys.toList())
        assertEquals(12, stats.allocMb)

        val page = api.adminUsers(0, 50)
        assertEquals(7, page.total)
        val user = page.users.single()
        assertTrue(user.isAdmin && user.isDisabled)
        assertEquals("b@x.com", user.email)

        val messages = api.adminMessages(before = 100, limit = 50)
        assertEquals(373022222882705408L, messages[0].msgId)
        assertEquals(ChatType.GROUP, messages[0].chatType)
        assertTrue(messages[0].recalled)
        assertEquals(ChatType.SINGLE, messages[1].chatType)
        assertEquals("100", requests.last().url.parameters["before"])

        api.adminDeleteMessage(373022222882705408L)
        api.adminDeleteUser("100001")
        assertTrue("msg_id=373022222882705408" in requests[requests.size - 2].form())
        val del = requests.last()
        assertEquals("/admin/users/delete", del.url.encodedPath)
        assertTrue("target_uid=100001" in del.form() && "token=jwt" in del.form())
    }

    @Test
    fun adminMessagesWithoutCursorOmitsBefore() = runTest {
        val api = api { respond("""{"messages":[],"limit":50}""", HttpStatusCode.OK, jsonHeaders) }
        assertTrue(api.adminMessages(before = null, limit = 50).isEmpty())
        assertEquals(null, requests.single().url.parameters["before"])
    }
}
