package com.goim.kmp.model

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminTest {
    private fun token(payload: String): String {
        val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        return "${b64.encode("""{"alg":"HS256"}""".encodeToByteArray())}.${b64.encode(payload.encodeToByteArray())}.sig"
    }

    @Test
    fun adminRoleComesFromJwtClaims() {
        assertTrue(User("1", "a", token("""{"uid":"1","role":"admin","exp":9}""")).isAdmin)
        assertFalse(User("2", "b", token("""{"uid":"2","role":"user"}""")).isAdmin)
        assertFalse(User("3", "c", token("""{"uid":"3"}""")).isAdmin)
    }

    @Test
    fun malformedTokenIsNotAdmin() {
        assertFalse(User("1", "a", "jwt").isAdmin)
        assertFalse(User("1", "a", "a.!!!.c").isAdmin)
        assertFalse(User("1", "a", "a.${Base64.UrlSafe.encode("not json".encodeToByteArray())}.c").isAdmin)
    }

    @Test
    fun adminMessagePreview() {
        val base = AdminMessage(1, "a", "b", ChatType.SINGLE, MsgType.Text, "hi", 1, recalled = false)
        assertEquals("hi", base.preview)
        assertEquals("[已撤回]", base.copy(recalled = true).preview)
        assertEquals("[文件] a.pdf", base.copy(msgType = MsgType.File, content = """{"file_id":"1","name":"a.pdf"}""").preview)
    }
}
