package com.goim.kmp.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * CmdSignal content：`{"type":"<ns>.<name>","data":...}`，对齐服务端 `EncodeSignal` / `ParseSignal`。
 * 无 type 或 type 为空时解析失败（网关会丢弃同类帧）。
 */
object SignalCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** 编码；[type] 为空返回 null。[dataJson] 非法 JSON 时按字符串包一层。 */
    fun encode(type: String, dataJson: String = "{}"): String? {
        if (type.isEmpty()) return null
        val data: JsonElement = runCatching { json.parseToJsonElement(dataJson.ifBlank { "{}" }) }
            .getOrElse { JsonPrimitive(dataJson) }
        return buildJsonObject {
            put("type", type)
            put("data", data)
        }.toString()
    }

    /** @return type to dataJson；失败返回 null。缺省 `data` 时 dataJson 为 `{}`。 */
    fun parse(content: String): Pair<String, String>? {
        val obj = runCatching { json.parseToJsonElement(content).jsonObject }.getOrNull() ?: return null
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
            ?: return null
        val dataJson = when (val data = obj["data"]) {
            null -> "{}"
            is JsonObject, is JsonPrimitive -> data.toString()
            else -> data.toString()
        }
        return type to dataJson
    }
}
