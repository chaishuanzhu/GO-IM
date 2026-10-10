package com.goim.kmp.network

import com.goim.kmp.common.DomainError
import com.goim.kmp.model.User
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
internal data class LoginResponseDto(val uid: String, val username: String, val token: String)

/** 网关 REST 接口（docs/07）。[baseUrl] 每次请求时读取，设置页改地址后即时生效。 */
class GoImApi(
    private val client: HttpClient,
    private val baseUrl: () -> String,
) {
    suspend fun login(uid: String, username: String, password: String): User =
        authForm("login", uid, username, password)

    suspend fun register(uid: String, username: String, password: String): User =
        authForm("register", uid, username, password)

    /**
     * 用 `GET /unread` 探测 token：true 有效、false 被拒（401）、null 无法判断（网络等）。
     * 用于 WS 握手失败时区分「鉴权失败」与「网络失败」（部分引擎拿不到握手状态码）。
     */
    suspend fun validateToken(uid: String, token: String): Boolean? = try {
        val response = client.get(url("unread")) {
            parameter("uid", uid)
            parameter("token", token)
            timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
        }
        when {
            response.status == HttpStatusCode.Unauthorized -> false
            response.status.isSuccess() -> true
            else -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun authForm(path: String, uid: String, username: String, password: String): User {
        val response = request {
            client.submitForm(
                url = url(path),
                formParameters = parameters {
                    append("uid", uid)
                    append("username", username)
                    append("password", password)
                },
            ) {
                timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
            }
        }
        val dto = response.decode<LoginResponseDto>()
        return User(uid = dto.uid, username = dto.username.ifEmpty { dto.uid }, token = dto.token)
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
        if (!response.status.isSuccess()) {
            throw DomainError.Server(response.status.value, response.bodyAsText().trim())
        }
        return response
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T = try {
        body<T>()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw DomainError.Server(status.value, "响应解析失败: ${e.message}")
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}
