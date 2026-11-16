package com.goim.kmp

import com.goim.kmp.common.PlatformContext
import com.goim.kmp.repository.SessionManager
import com.goim.sdk.lite.GoImClient
import com.goim.sdk.lite.GoImConfig
import com.goim.sdk.lite.GoImIdentity
import com.goim.sdk.lite.GoImSdkPreview

/**
 * 示范：宿主自有账号体系拿到 GO-IM `uid`+`token` 后启动会话（不走 [com.goim.kmp.navigation.AuthNavHost]）。
 *
 * Paircast 类接入方：业务后端 `/server/v1/users/token` → 客户端调用本方法 → [com.goim.sdk.full.GoImNavHost]。
 */
@GoImSdkPreview
object ExternalIdentitySample {
    /** 独立 Lite 客户端（嵌入式宿主常用）。 */
    fun createClient(serverUrl: String, context: PlatformContext, deviceId: String = ""): GoImClient =
        GoImClient.create(GoImConfig(serverUrl = serverUrl, deviceId = deviceId), context)

    /** 复用 App 已有 [SessionManager]（示范 App 登录页旁路）。 */
    suspend fun openOnManager(manager: SessionManager, uid: String, token: String, nickname: String? = null) {
        manager.openSession(
            com.goim.kmp.model.User(uid = uid, nickname = nickname.orEmpty().ifBlank { uid }, token = token),
        )
    }

    suspend fun openOnClient(client: GoImClient, uid: String, token: String, nickname: String? = null) {
        client.openSession(GoImIdentity(uid = uid, token = token, nickname = nickname))
    }
}
