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

        val user = api.login("alice", "", "pw")

        assertEquals(User("alice", "alice", "jwt"), user)
        assertEquals("http://host:8080/login", seenUrl)
        assertTrue("uid=alice" in seenBody && "password=pw" in seenBody)
    }

    @Test
    fun serverErrorMapsToDomainError() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Conflict, "user exists\n") }
        val api = GoImApi(createHttpClient(engine)) { "http://h" }

        val error = assertFailsWith<DomainError.Server> { api.register("a", "a", "p") }
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
