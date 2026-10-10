package com.goim.kmp.common

import kotlin.test.Test
import kotlin.test.assertEquals

class UserMessageTest {
    @Test
    fun knownServerTextsAreTranslated() {
        assertEquals("已在群中", DomainError.Server(409, "already a member").userMessage())
        assertEquals(
            "已发送过请求或已是好友",
            DomainError.Server(409, "friend request already exists between a and b").userMessage(),
        )
        assertEquals("仅群主可以执行此操作", DomainError.Server(403, "only the group owner can invite members").userMessage())
        assertEquals("服务端未启用该功能", DomainError.Server(503, "friend system not available").userMessage())
    }

    @Test
    fun unknownTextsFallBackToStatusCode() {
        assertEquals("没有权限", DomainError.Server(403, "nope").userMessage())
        assertEquals("服务器错误 (500)：boom", DomainError.Server(500, "boom").userMessage())
        assertEquals("网络不可用：timeout", DomainError.Network("timeout").userMessage())
        assertEquals("x", IllegalStateException("x").userMessage())
    }
}
