package com.goim.kmp.network

import com.goim.kmp.common.DomainError
import com.goim.kmp.model.FileMeta
import com.goim.kmp.model.StickerItem
import com.goim.kmp.model.StickerPack
import com.goim.kmp.model.User
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onUpload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal data class UploadResponseDto(
    @SerialName("file_id") val fileId: JsonElement? = null,
    val name: String? = null,
    val size: Long? = null,
    val mime: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("thumb_width") val thumbWidth: Int? = null,
    @SerialName("thumb_height") val thumbHeight: Int? = null,
)

@Serializable
internal data class PresignResponseDto(
    @SerialName("file_id") val fileId: String,
    @SerialName("upload_url") val uploadUrl: String,
    val method: String = "PUT",
    val headers: Map<String, String> = emptyMap(),
)

/**
 * 文件上传与下载，鉴权同其它 REST 接口（`uid` + `token`）。
 * 上传优先 `/file/presign` → PUT 直传对象存储 → `/file/complete`；
 * 服务端未开启直传或对象存储不可达时，对不超过 [LEGACY_MAX_BYTES] 的文件回退到 multipart `/upload`。
 */
class GoImFileApi(
    private val client: HttpClient,
    private val baseUrl: () -> String,
    private val user: User,
) {
    /** 返回服务端识别出的元数据；`0` 尺寸视为缺失（同 iOS `positive`）。 */
    suspend fun upload(
        bytes: ByteArray,
        fileName: String,
        mime: String,
        onProgress: (Float) -> Unit = {},
    ): FileMeta {
        val direct = try {
            uploadDirect(bytes, fileName, mime, onProgress)
        } catch (_: DirectUploadUnavailable) {
            null
        }
        if (direct != null) return direct
        if (bytes.size > LEGACY_MAX_BYTES) {
            throw DomainError.InvalidState("当前服务器仅支持 ${LEGACY_MAX_BYTES / 1024 / 1024}MB 以内的文件")
        }
        return uploadViaGateway(bytes, fileName, mime, onProgress)
    }

    private suspend fun uploadDirect(
        bytes: ByteArray,
        fileName: String,
        mime: String,
        onProgress: (Float) -> Unit,
    ): FileMeta {
        val presignResponse = postForm(
            "file/presign",
            "name" to fileName,
            "size" to bytes.size.toString(),
            "mime" to mime,
        )
        if (presignResponse.status.value in DIRECT_UNSUPPORTED) throw DirectUploadUnavailable()
        ensureSuccess(presignResponse)
        val presign = decode(presignResponse, PresignResponseDto.serializer())

        val contentType = presign.headers.entries
            .firstOrNull { it.key.equals(HttpHeaders.ContentType, ignoreCase = true) }?.value
            ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
            ?: ContentType.Application.OctetStream
        val putResponse = try {
            client.request(presign.uploadUrl) {
                method = HttpMethod.parse(presign.method.uppercase())
                setBody(ByteArrayContent(bytes, contentType))
                timeout { requestTimeoutMillis = TRANSFER_TIMEOUT_MS }
                onUpload { sent, total ->
                    val all = total ?: bytes.size.toLong()
                    if (all > 0) onProgress((sent.toFloat() / all).coerceIn(0f, 0.99f))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 对象存储域名不可达：交给调用方回退到网关中转
            throw DirectUploadUnavailable()
        }
        ensureSuccess(putResponse)

        val completeResponse = postForm("file/complete", "file_id" to presign.fileId)
        ensureSuccess(completeResponse)
        return toFileMeta(completeResponse, bytes.size.toLong(), fileName, mime)
    }

    private suspend fun postForm(path: String, vararg fields: Pair<String, String>): HttpResponse = try {
        client.submitForm(
            url = url(path),
            formParameters = parameters {
                append("uid", user.uid)
                append("token", user.token)
                fields.forEach { (k, v) -> append(k, v) }
            },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw DomainError.Network(e.message ?: e::class.simpleName ?: "network error")
    }

    private suspend fun uploadViaGateway(
        bytes: ByteArray,
        fileName: String,
        mime: String,
        onProgress: (Float) -> Unit,
    ): FileMeta {
        val response = request {
            client.submitFormWithBinaryData(
                url = url("upload"),
                formData = formData {
                    append("uid", user.uid)
                    append("token", user.token)
                    append(
                        "file",
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, mime)
                            append(HttpHeaders.ContentDisposition, "filename=\"${fileName.replace("\"", "")}\"")
                        },
                    )
                },
            ) {
                timeout { requestTimeoutMillis = TRANSFER_TIMEOUT_MS }
                onUpload { sent, total ->
                    val all = total ?: bytes.size.toLong()
                    if (all > 0) onProgress((sent.toFloat() / all).coerceIn(0f, 1f))
                }
            }
        }
        return toFileMeta(response, bytes.size.toLong(), fileName, mime)
    }

    private suspend fun <T> decode(response: HttpResponse, serializer: KSerializer<T>): T = try {
        apiJson.decodeFromString(serializer, response.bodyAsText())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw DomainError.Server(response.status.value, "响应解析失败: ${e.message}")
    }

    private suspend fun toFileMeta(response: HttpResponse, size: Long, fileName: String, mime: String): FileMeta {
        val dto = decode(response, UploadResponseDto.serializer())
        val fileId = (dto.fileId as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }
            ?: throw DomainError.Server(response.status.value, "上传响应缺少 file_id")
        return FileMeta(
            fileId = fileId,
            name = dto.name?.takeIf { it.isNotEmpty() } ?: fileName,
            size = dto.size ?: size,
            mime = dto.mime?.takeIf { it.isNotEmpty() } ?: mime,
            width = dto.width?.takeIf { it > 0 },
            height = dto.height?.takeIf { it > 0 },
            thumbWidth = dto.thumbWidth?.takeIf { it > 0 },
            thumbHeight = dto.thumbHeight?.takeIf { it > 0 },
        )
    }

    /** `/file?id=&uid=&token=[&thumb=1]`；同时供图片加载器直接使用。 */
    fun fileUrl(fileId: String, thumb: Boolean = false): String {
        val (uid, token) = user.uid to user.token
        return URLBuilder(url("file")).apply {
            parameters.append("id", fileId)
            parameters.append("uid", uid)
            parameters.append("token", token)
            if (thumb) parameters.append("thumb", "1")
        }.buildString()
    }

    /** 分块写给 [sink]；无 Content-Length 时不回调进度。 */
    suspend fun download(
        fileId: String,
        thumb: Boolean = false,
        onProgress: (Float) -> Unit = {},
        sink: suspend (chunk: ByteArray, length: Int) -> Unit,
    ) {
        stream(fileUrl(fileId, thumb), onProgress, sink)
    }

    suspend fun downloadBytes(fileId: String, thumb: Boolean = false): ByteArray =
        fetchBytes(client, fileUrl(fileId, thumb))

    private suspend fun stream(
        url: String,
        onProgress: (Float) -> Unit,
        sink: suspend (ByteArray, Int) -> Unit,
    ) {
        try {
            client.prepareGet(url) {
                timeout { requestTimeoutMillis = TRANSFER_TIMEOUT_MS }
            }.execute { response ->
                ensureSuccess(response)
                val total = response.contentLength() ?: -1L
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(CHUNK)
                var received = 0L
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) {
                        if (channel.isClosedForRead) break
                        continue
                    }
                    sink(buffer, n)
                    received += n
                    if (total > 0) onProgress((received.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DomainError) {
            throw e
        } catch (e: Exception) {
            throw DomainError.Network(e.message ?: e::class.simpleName ?: "network error")
        }
    }

    private fun url(path: String) = baseUrl().trimEnd('/') + "/" + path

    private suspend fun request(block: suspend () -> HttpResponse): HttpResponse {
        val response = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DomainError.Network(e.message ?: e::class.simpleName ?: "network error")
        }
        ensureSuccess(response)
        return response
    }

    private class DirectUploadUnavailable : Exception()

    companion object {
        /** 服务端默认 `object_storage.legacy_max_upload`（经网关中转的上限）。 */
        const val LEGACY_MAX_BYTES = 10 * 1024 * 1024
        private const val TRANSFER_TIMEOUT_MS = 600_000L
        private const val CHUNK = 64 * 1024

        /** presign 返回这些状态码表示服务端不支持直传。 */
        private val DIRECT_UNSUPPORTED = setOf(404, 405, 501)
    }
}

private suspend fun ensureSuccess(response: HttpResponse) {
    if (!response.status.isSuccess()) {
        throw DomainError.Server(response.status.value, response.bodyAsText().trim())
    }
}

internal suspend fun fetchBytes(client: HttpClient, url: String): ByteArray {
    val response = try {
        client.get(url) { timeout { requestTimeoutMillis = 60_000L } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw DomainError.Network(e.message ?: e::class.simpleName ?: "network error")
    }
    ensureSuccess(response)
    return response.bodyAsBytes()
}

@Serializable
internal data class CatalogDto(
    @SerialName("cdn_base") val cdnBase: String? = null,
    val packs: List<CatalogPackDto> = emptyList(),
)

@Serializable
internal data class CatalogPackDto(
    @SerialName("pack_id") val packId: String,
    val name: String = "",
    val version: Int = 1,
    @SerialName("base_url") val baseUrl: String? = null,
    val cover: String? = null,
    val count: Int = 0,
)

@Serializable
internal data class PackManifestDto(
    @SerialName("pack_id") val packId: String,
    val name: String = "",
    val version: Int = 1,
    val cover: String? = null,
    @SerialName("base_url") val baseUrl: String? = null,
    val stickers: List<StickerDto> = emptyList(),
)

@Serializable
internal data class StickerDto(val id: String, val file: String, val w: Int = 240, val h: Int = 240)

/** 远端贴纸目录条目（未下载 `pack.json` 前只有封面与数量）。 */
data class CatalogPack(
    val packId: String,
    val name: String,
    val version: Int,
    val baseUrl: String,
    val cover: String?,
    val count: Int,
)

/** 贴纸 CDN：`catalog.json` → 每包 `pack.json` → `{base_url}{file}`；不需要登录。 */
class StickerCdnApi(private val client: HttpClient) {
    suspend fun catalog(url: String = CATALOG_URL): List<CatalogPack> = parseCatalog(fetchBytes(client, url).decodeToString())

    suspend fun pack(baseUrl: String): StickerPack = parsePackManifest(fetchBytes(client, normalized(baseUrl) + "pack.json").decodeToString(), baseUrl)

    suspend fun bytes(url: String): ByteArray = fetchBytes(client, url)

    companion object {
        const val CDN_BASE = "https://oss.chaisz.com/im-sticker-pack"
        const val CATALOG_URL = "$CDN_BASE/catalog.json"

        fun normalized(baseUrl: String) = if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/"

        fun parseCatalog(text: String): List<CatalogPack> {
            val dto = apiJson.decodeFromString(CatalogDto.serializer(), text)
            val cdn = dto.cdnBase?.trimEnd('/') ?: CDN_BASE
            return dto.packs.map {
                CatalogPack(
                    packId = it.packId,
                    name = it.name.ifEmpty { it.packId },
                    version = it.version,
                    baseUrl = normalized(it.baseUrl?.takeIf { u -> u.isNotEmpty() } ?: "$cdn/${it.packId}/"),
                    cover = it.cover,
                    count = it.count,
                )
            }
        }

        fun parsePackManifest(text: String, fallbackBaseUrl: String): StickerPack {
            val dto = apiJson.decodeFromString(PackManifestDto.serializer(), text)
            return StickerPack(
                packId = dto.packId,
                name = dto.name.ifEmpty { dto.packId },
                version = dto.version,
                coverFileName = dto.cover,
                stickers = dto.stickers.map { StickerItem(dto.packId, it.id, it.file, it.w, it.h) },
                baseUrl = normalized(dto.baseUrl?.takeIf { it.isNotEmpty() } ?: fallbackBaseUrl),
            )
        }
    }
}
