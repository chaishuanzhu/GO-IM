package com.goim.kmp.network

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpLoggingTest {
    @Test
    fun redactsTokenAndPassword() {
        assertEquals(
            "http://h:8080/file?id=42&uid=alice&token=***&thumb=1",
            redactedUrl(Url("http://h:8080/file?id=42&uid=alice&token=jwt.secret&thumb=1")),
        )
        assertEquals("https://im.chaisz.com/login?password=***", redactedUrl(Url("https://im.chaisz.com/login?password=p")))
        assertEquals("https://im.chaisz.com/", redactedUrl(Url("https://im.chaisz.com")))
    }
}
