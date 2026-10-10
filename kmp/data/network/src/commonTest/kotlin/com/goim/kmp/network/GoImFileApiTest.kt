package com.goim.kmp.network

import com.goim.kmp.common.DomainError
import com.goim.kmp.model.User
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoImFileApiTest {
    private val me = User("alice", "alice", "jwt")
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(engine: MockEngine) = GoImFileApi(createHttpClient(engine), { "http://host:8080/" }, me)

    @Test
    fun uploadSendsMultipartAndParsesNumericId() = runTest {
        val engine = MockEngine { request ->
            requests += request
            respond(
                """{"file_id":366821983901454336,"name":"pic.png","size":88,"mime":"image/png","width":64,"height":48,"thumb_width":0,"thumb_height":0}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val progress = mutableListOf<Float>()
        val meta = api(engine).upload(ByteArray(88), "pic.png", "image/png") { progress += it }
        assertEquals("366821983901454336", meta.fileId)
        assertEquals(64, meta.width)
        assertNull(meta.thumbWidth, "0 视为缺失")
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("http://host:8080/upload", request.url.toString())
        val body = (request.body as OutgoingContent).contentType.toString()
        assertTrue(body.startsWith("multipart/form-data"), body)
    }

    @Test
    fun uploadErrorsMapToDomain() = runTest {
        val engine = MockEngine { respond("file too large", HttpStatusCode.PayloadTooLarge) }
        val error = assertFailsWith<DomainError.Server> { api(engine).upload(ByteArray(1), "a", "b") }
        assertEquals(413, error.code)
        val missing = MockEngine { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        assertFailsWith<DomainError.Server> { api(missing).upload(ByteArray(1), "a", "b") }
    }

    @Test
    fun downloadStreamsChunksWithProgress() = runTest {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val engine = MockEngine { request ->
            requests += request
            respond(ByteReadChannel(payload), headers = headersOf(HttpHeaders.ContentLength, payload.size.toString()))
        }
        val received = mutableListOf<Byte>()
        val progress = mutableListOf<Float>()
        api(engine).download("42", thumb = true, onProgress = { progress += it }) { chunk, n ->
            for (i in 0 until n) received += chunk[i]
        }
        assertContentEquals(payload, received.toByteArray())
        assertEquals(1f, progress.last())
        assertEquals("http://host:8080/file?id=42&uid=alice&token=jwt&thumb=1", requests.single().url.toString())
    }

    @Test
    fun downloadFailureIsServerError() = runTest {
        val engine = MockEngine { respond("file not found", HttpStatusCode.NotFound) }
        val error = assertFailsWith<DomainError.Server> { api(engine).download("x") { _, _ -> } }
        assertEquals(404, error.code)
    }

    @Test
    fun stickerCatalogAndManifestParsing() {
        val packs = StickerCdnApi.parseCatalog(
            """{"cdn_base":"https://cdn/x","packs":[{"pack_id":"a","name":"A","version":2,"cover":"1.png","count":3},
               {"pack_id":"b","name":"","base_url":"https://other/b","count":1}]}""",
        )
        assertEquals("https://cdn/x/a/", packs[0].baseUrl)
        assertEquals("https://other/b/", packs[1].baseUrl)
        assertEquals("b", packs[1].name)
        val pack = StickerCdnApi.parsePackManifest(
            """{"pack_id":"capoo","name":"咖波","version":1,"stickers":[{"id":"001","file":"001.gif","w":240,"h":240}]}""",
            "https://cdn/capoo",
        )
        assertEquals("https://cdn/capoo/", pack.baseUrl)
        assertEquals("gif", pack.stickers.single().asRef().format)
    }
}
