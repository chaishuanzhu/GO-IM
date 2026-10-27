package com.goim.kmp.common

/** 领域层统一错误；文案与 iOS `DomainError.errorDescription` 一致。 */
sealed class DomainError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotAuthenticated : DomainError("未登录")

    class Network(val reason: String, cause: Throwable? = null) : DomainError(reason, cause)

    class Server(val code: Int, val body: String) : DomainError("服务器错误 ($code): $body")

    class Persistence(val reason: String, cause: Throwable? = null) : DomainError("本地存储错误: $reason", cause)

    class InvalidState(val reason: String) : DomainError(reason)

    class Cancelled : DomainError("已取消")
}

/** 面向用户的提示：把服务端英文纯文本错误翻成中文，未知内容原样带出。 */
fun Throwable.userMessage(): String = when (this) {
    is DomainError.Server -> serverMessage(code, body)
    is DomainError.Network -> "网络不可用：$reason"
    else -> message ?: "操作失败"
}

private val serverTexts = listOf(
    "invalid account or password" to "账号或密码错误",
    "account disabled" to "账号已被禁用",
    "password required" to "请输入密码",
    "email already registered" to "该邮箱已被注册",
    "email not registered" to "该邮箱未注册",
    "invalid or expired verification code" to "验证码错误或已过期",
    "requested too frequently" to "验证码发送太频繁，请稍后再试",
    "too many verification codes" to "验证码发送次数已达上限，请稍后再试",
    "failed to send email" to "邮件发送失败，请稍后重试",
    "invalid email" to "邮箱格式不正确",
    "password must be at least" to "密码至少 6 位",
    "password too long" to "密码过长",
    "invalid current password" to "当前密码错误",
    "nickname required" to "昵称不能为空",
    "nickname too long" to "昵称最多 32 个字",
    "nickname contains invalid" to "昵称包含不支持的字符",
    "avatar must be an image" to "头像必须是图片",
    "avatar file not found" to "头像图片无效，请重新上传",
    "query too long" to "搜索内容过长",
    "already a member" to "已在群中",
    "friend request already exists" to "已发送过请求或已是好友",
    "already friends" to "已经是好友",
    "cannot friend yourself" to "不能添加自己为好友",
    "cannot invite yourself" to "不能邀请自己",
    "only the group owner" to "仅群主可以执行此操作",
    "group not found" to "群不存在",
    "user not found" to "用户不存在",
    "not a member" to "不在该群中",
    "registration closed" to "已关闭注册，账号由管理员分配",
    "mail not available" to "服务端未配置邮件，无法发送初始密码",
    "not available" to "服务端未启用该功能",
    "no pending" to "该请求已处理",
    "admin access required" to "需要管理员权限（角色变更后请重新登录）",
    "cannot delete yourself" to "不能删除自己",
    "not found" to "不存在或已删除",
)

private fun serverMessage(code: Int, body: String): String {
    val lower = body.lowercase()
    serverTexts.firstOrNull { lower.contains(it.first) }?.let { return it.second }
    return when (code) {
        401 -> "登录已过期，请重新登录"
        403 -> "没有权限"
        404 -> "不存在或已处理"
        429 -> "操作过于频繁，请稍后再试"
        503 -> "服务端未启用该功能"
        else -> "服务器错误 ($code)${if (body.isBlank()) "" else "：$body"}"
    }
}
