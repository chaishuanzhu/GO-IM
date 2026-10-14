package com.goim.kmp.network

import com.goim.kmp.common.DomainError
import com.goim.kmp.model.User
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.decodeURLQueryComponent
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

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val metaJson =
        """{"file_id":366821983901454336,"name":"pic.png","size":88,"mime":"image/png","width":64,"height":48,"thumb_width":0,"thumb_height":0}"""

    @Test
    fun uploadDirectPresignPutComplete() = runTest {
        val payload = ByteArray(88) { it.toByte() }
        var putBody = ByteArray(0)
        val engine = MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/file/presign" -> respond(
                    """{"file_id":"366821983901454336","upload_url":"https://oss.test/im-files/366821983901454336?X-Amz-Signature=abc",
                       "method":"PUT","headers":{"Content-Type":"image/png"},"expires_at":1,"max_size":104857600}""",
                    headers = json,
                )
                "/im-files/366821983901454336" -> {
                    putBody = request.body.toByteArray()
                    respond("", HttpStatusCode.OK)
                }
                "/file/complete" -> respond(metaJson, headers = json)
                else -> error("unexpected ${request.url}")
            }
        }
        val meta = api(engine).upload(payload, "pic.png", "image/png")
        assertEquals("366821983901454336", meta.fileId)
        assertEquals(64, meta.width)
        assertNull(meta.thumbWidth, "0 视为缺失")

        assertEquals(listOf("/file/presign", "/im-files/366821983901454336", "/file/complete"), requests.map { it.url.encodedPath })
        val presign = formOf(requests[0])
        assertEquals(mapOf("uid" to "alice", "token" to "jwt", "name" to "pic.png", "size" to "88", "mime" to "image/png"), presign)
        val put = requests[1]
        assertEquals(HttpMethod.Put, put.method)
        assertEquals("https://oss.test/im-files/366821983901454336?X-Amz-Signature=abc", put.url.toString())
        assertEquals("image/png", put.body.contentType.toString())
        assertContentEquals(payload, putBody)
        assertEquals("366821983901454336", formOf(requests[2])["file_id"])
    }

    @Test
    fun uploadFallsBackToMultipartWhenDirectDisabled() = runTest {
        val engine = MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/file/presign" -> respond("direct upload not enabled", HttpStatusCode.NotImplemented)
                "/upload" -> respond(metaJson, headers = json)
                else -> error("unexpected ${request.url}")
            }
        }
        val meta = api(engine).upload(ByteArray(88), "pic.png", "image/png")
        assertEquals("366821983901454336", meta.fileId)
        val upload = requests.last()
        assertEquals(HttpMethod.Post, upload.method)
        assertEquals("http://host:8080/upload", upload.url.toString())
        val type = (upload.body as OutgoingContent).contentType.toString()
        assertTrue(type.startsWith("multipart/form-data"), type)
    }

    @Test
    fun uploadFallsBackWhenObjectStorageUnreachable() = runTest {
        val engine = MockEngine { request ->
            requests += request
            when (request.url.host) {
                "oss.test" -> throw IllegalStateException("connect refused")
                else -> when (request.url.encodedPath) {
                    "/file/presign" -> respond(
                        """{"file_id":"1","upload_url":"https://oss.test/im-files/1","headers":{"Content-Type":"image/png"}}""",
                        headers = json,
                    )
                    "/upload" -> respond(metaJson, headers = json)
                    else -> error("unexpected ${request.url}")
                }
            }
        }
        assertEquals("366821983901454336", api(engine).upload(ByteArray(88), "pic.png", "image/png").fileId)
        assertEquals("/upload", requests.last().url.encodedPath)
    }

    @Test
    fun largeFileWithoutDirectUploadIsRejected() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.NotImplemented) }
        assertFailsWith<DomainError.InvalidState> {
            api(engine).upload(ByteArray(GoImFileApi.LEGACY_MAX_BYTES + 1), "big.bin", "application/octet-stream")
        }
    }

    private fun formOf(request: HttpRequestData): Map<String, String> {
        val text = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        return text.split('&').associate {
            val (k, v) = it.split('=', limit = 2)
            k.decodeURLQueryComponent() to v.decodeURLQueryComponent(plusIsSpace = true)
        }
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
