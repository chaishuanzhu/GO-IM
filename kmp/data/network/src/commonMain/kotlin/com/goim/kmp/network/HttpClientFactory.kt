package com.goim.kmp.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

internal val apiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** OkHttp（Android / Desktop）或 Darwin（iOS）。 */
expect fun defaultHttpEngine(): HttpClientEngine

/** 全局共用一个客户端：REST + WebSocket。单次请求超时在 [GoImApi] 里按请求设置。 */
fun createHttpClient(engine: HttpClientEngine = defaultHttpEngine()): HttpClient =
    HttpClient(engine) { configure() }

internal fun HttpClientConfig<*>.configure() {
    expectSuccess = false
    install(ContentNegotiation) { json(apiJson) }
    install(WebSockets)
    install(HttpLogging)
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
    }
}
