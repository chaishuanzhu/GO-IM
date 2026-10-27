package com.goim.kmp.network

import com.goim.kmp.common.DomainError
import com.goim.kmp.model.EmailCodePurpose
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class LoginResponseDto(
    val uid: String,
    val token: String,
    val nickname: String? = null,
    /** 1.3.0 之前的网关只返回 username。 */
    val username: String? = null,
    val email: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("must_change_password") val mustChangePassword: Boolean = false,
)

@Serializable
internal data class AuthConfigDto(
    @SerialName("registration_open") val registrationOpen: Boolean = true,
)

/** 网关 REST 接口（docs/07）。[baseUrl] 每次请求时读取，设置页改地址后即时生效。 */
class GoImApi(
    private val client: HttpClient,
    private val baseUrl: () -> String,
) {
    /** [account] 为邮箱或用户 ID。 */
    suspend fun login(account: String, password: String): User =
        authForm("login", "account" to account, "password" to password)

    /** 邮箱 + 验证码注册，服务端分配用户 ID。 */
    suspend fun register(email: String, code: String, password: String): User =
        authForm("register", "email" to email, "code" to code, "password" to password)

    /** 发送注册 / 找回密码验证码（绑定邮箱需登录，见 [GoImUserApi.sendBindEmailCode]）。 */
    suspend fun sendEmailCode(email: String, purpose: EmailCodePurpose) {
        submit("auth/email-code", "email" to email, "purpose" to purpose.raw)
    }

    /** 管理后台是否开放自助注册；旧网关没有该接口或请求失败时按开放处理，由注册接口再做最终判定。 */
    suspend fun registrationOpen(): Boolean = try {
        request {
            client.get(url("auth/config")) { timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS } }
        }.decode<AuthConfigDto>().registrationOpen
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        true
    }

    suspend fun resetPassword(email: String, code: String, newPassword: String) {
        submit("password/reset", "email" to email, "code" to code, "new_password" to newPassword)
    }

    /** 已登录改密码（会话尚未保存时也可用，凭据直接取自 [user]）；同时清除服务端「须改密码」标记。 */
    suspend fun changePassword(user: User, oldPassword: String, newPassword: String) {
        submit(
            "change-password",
            "uid" to user.uid,
            "token" to user.token,
            "old_password" to oldPassword,
            "new_password" to newPassword,
        )
    }

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

    private suspend fun authForm(path: String, vararg fields: Pair<String, String>): User {
        val dto = submit(path, *fields).decode<LoginResponseDto>()
        return User(
            uid = dto.uid,
            nickname = (dto.nickname ?: dto.username).orEmpty().ifEmpty { dto.uid },
            token = dto.token,
            email = dto.email.orEmpty(),
            avatarUrl = absoluteUrl(baseUrl(), dto.avatarUrl),
            mustChangePassword = dto.mustChangePassword,
        )
    }

    private suspend fun submit(path: String, vararg fields: Pair<String, String>): HttpResponse = request {
        client.submitForm(
            url = url(path),
            formParameters = parameters { fields.forEach { (k, v) -> append(k, v) } },
        ) {
            timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
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

/** 网关返回的头像等相对路径（`/avatar/1`）补全为绝对地址；空值返回空串。 */
internal fun absoluteUrl(base: String, path: String?): String = when {
    path.isNullOrBlank() -> ""
    path.startsWith("http://") || path.startsWith("https://") -> path
    else -> base.trimEnd('/') + "/" + path.trimStart('/')
}
