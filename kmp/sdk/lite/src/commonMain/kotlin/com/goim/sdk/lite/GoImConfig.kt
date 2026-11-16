package com.goim.sdk.lite

import com.goim.kmp.model.TransportKind

/**
 * Lite SDK 启动配置。
 *
 * [deviceId] / [platform] 会写入设备偏好并在建连时上报网关；同一设备重连会顶掉自己的旧连接。
 * [platform] 取值与网关约定一致：`android` / `ios` / `ipados` / `macos` / `windows` / `linux`。
 */
data class GoImConfig(
    val serverUrl: String,
    val transport: TransportKind = TransportKind.WEB_SOCKET,
    val deviceId: String = "",
    val platform: String = "",
)

/**
 * 宿主登录后注入的身份。令牌由接入方后端通过 `/server/v1/users/token`（或等价）签发。
 */
data class GoImIdentity(
    val uid: String,
    val token: String,
    val nickname: String? = null,
    val email: String = "",
    val avatarUrl: String = "",
)

/** 标记 0.x 预览 API，后续版本可能不兼容。 */
@RequiresOptIn(message = "GO-IM SDK preview API — subject to change before 1.0")
@Retention(AnnotationRetention.BINARY)
annotation class GoImSdkPreview
