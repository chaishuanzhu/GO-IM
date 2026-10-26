package com.goim.kmp.network

import com.goim.kmp.common.DomainError
import com.goim.kmp.model.User
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoImApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun loginPostsFormAndParsesUser() = runTest {
        var seenBody = ""
        var seenUrl = ""
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            seenBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond("""{"uid":"alice","username":"","token":"jwt","extra":1}""", HttpStatusCode.OK, jsonHeaders)
        }
        val api = GoImApi(createHttpClient(engine)) { "http://host:8080/" }

        val user = api.login("alice", "pw")

        assertEquals(User("alice", "alice", "jwt"), user)
        assertEquals("http://host:8080/login", seenUrl)
        assertTrue("account=alice" in seenBody && "password=pw" in seenBody)
    }

    @Test
    fun registerParsesProfileAndResolvesAvatar() = runTest {
        var seenBody = ""
        val engine = MockEngine { request ->
            seenBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond(
                """{"uid":"100000","nickname":"小明","username":"小明","email":"a@b.co","avatar_url":"/avatar/9","token":"jwt"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val api = GoImApi(createHttpClient(engine)) { "https://im.example.com/" }

        val user = api.register("a@b.co", "123456", "secret")

        assertEquals(User("100000", "小明", "jwt", email = "a@b.co", avatarUrl = "https://im.example.com/avatar/9"), user)
        assertTrue("email=a%40b.co" in seenBody && "code=123456" in seenBody && "password=secret" in seenBody)
    }

    @Test
    fun mustChangePasswordAndChangePassword() = runTest {
        var seenPath = ""
        var seenBody = ""
        val engine = MockEngine { request ->
            seenPath = request.url.encodedPath
            seenBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            if (seenPath == "/login") {
                respond("""{"uid":"100000","nickname":"admin","token":"jwt","must_change_password":true}""", HttpStatusCode.OK, jsonHeaders)
            } else {
                respond("""{"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
            }
        }
        val api = GoImApi(createHttpClient(engine)) { "http://h" }

        val user = api.login("admin@example.com", "Initial123")
        assertTrue(user.mustChangePassword)

        api.changePassword(user, "Initial123", "newpass1")
        assertEquals("/change-password", seenPath)
        listOf("uid=100000", "token=jwt", "old_password=Initial123", "new_password=newpass1").forEach {
            assertTrue(it in seenBody, "missing $it in $seenBody")
        }
    }

    @Test
    fun serverErrorMapsToDomainError() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Conflict, "user exists\n") }
        val api = GoImApi(createHttpClient(engine)) { "http://h" }

        val error = assertFailsWith<DomainError.Server> { api.register("a@b.co", "123456", "p") }
        assertEquals(409, error.code)
        assertEquals("user exists", error.body)
    }

    @Test
    fun validateToken() = runTest {
        var status = HttpStatusCode.Unauthorized
        val engine = MockEngine { request ->
            assertEquals("/unread", request.url.encodedPath)
            assertEquals("tok", request.url.parameters["token"])
            respond("{}", status, jsonHeaders)
        }
        val api = GoImApi(createHttpClient(engine)) { "http://h" }

        assertEquals(false, api.validateToken("a", "tok"))
        status = HttpStatusCode.OK
        assertEquals(true, api.validateToken("a", "tok"))
        status = HttpStatusCode.BadGateway
        assertEquals(null, api.validateToken("a", "tok"))
    }
}
