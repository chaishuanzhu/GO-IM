package com.goim.kmp.network

import co.touchlab.kermit.Logger
import com.goim.kmp.common.nowMillis
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.request
import io.ktor.http.Url
import io.ktor.util.AttributeKey

private val startKey = AttributeKey<Long>("goim.http.start")
private val sensitiveParams = setOf("token", "password")

/** 请求 / 响应摘要写入 Kermit（tag `HTTP`），供开发者工具的网络日志查看；不记录 body，token 打码。 */
internal val HttpLogging = createClientPlugin("GoImHttpLogging") {
    val log = Logger.withTag("HTTP")
    onRequest { request, _ ->
        request.attributes.put(startKey, nowMillis())
        log.d { "→ ${request.method.value} ${redactedUrl(request.url.build())}" }
    }
    onResponse { response ->
        val call = response.call
        val started = call.attributes.getOrNull(startKey)
        val elapsed = started?.let { " ${nowMillis() - it}ms" }.orEmpty()
        val line = "← ${response.status.value} ${call.request.method.value} ${redactedUrl(response.request.url)}$elapsed"
        if (response.status.value >= 400) log.w { line } else log.d { line }
    }
}

internal fun redactedUrl(url: Url): String {
    val query = url.parameters.entries().joinToString("&") { (name, values) ->
        values.joinToString("&") { value -> "$name=${if (name in sensitiveParams) "***" else value}" }
    }
    return buildString {
        append(url.protocol.name).append("://").append(url.host)
        if (url.port != url.protocol.defaultPort) append(':').append(url.port)
        append(url.encodedPath.ifEmpty { "/" })
        if (query.isNotEmpty()) append('?').append(query)
    }
}
