package com.xxt.zl.ai.ruyiremote.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.UUID

/** 允许的 Remote 消息类型。 */
val REMOTE_MESSAGE_TYPES = setOf("rpc.request", "rpc.response", "rpc.error", "event")

/**
 * `{v: 1, id, type, timestamp, payload}`，只出现在 Noise 通道内部。
 *
 * 对应 Dart `protocol/messages.dart` 的 `RemoteMessage`。
 */
class RemoteMessage(
    val type: String,
    val payload: Any?,
    id: String? = null,
    timestamp: Long? = null,
) {
    val id: String = id ?: UuidV7.next()
    val timestamp: Long = timestamp ?: (System.currentTimeMillis())

    fun toJson(): JsonObject = buildJsonObject {
        put("v", PROTOCOL_VERSION)
        put("id", id)
        put("type", type)
        put("timestamp", timestamp)
        put("payload", payload?.let { JsonValues.toJsonElement(it) } ?: kotlinx.serialization.json.JsonNull)
    }

    fun encode(): ByteArray = JsonValues.encode(JsonValues.encodeToString(toJson()))

    companion object {
        fun rpcRequest(method: String, params: Any?, id: String? = null): RemoteMessage =
            RemoteMessage(
                "rpc.request",
                buildJsonObject {
                    put("method", method)
                    put("params", params?.let { JsonValues.toJsonElement(it) } ?: kotlinx.serialization.json.JsonNull)
                },
                id = id,
            )

        /** 严格解码（fail closed）：UTF-8 JSON、v1、已知类型、id 与 timestamp 合法。 */
        fun decode(bytes: ByteArray): RemoteMessage {
            val text = try {
                bytes.toString(Charsets.UTF_8)
            } catch (e: Exception) {
                throw ProtocolError("Remote message is not valid UTF-8 JSON.")
            }
            val map = JsonValues.parseObject(text)
                ?: throw ProtocolError("Remote message is not a JSON object.")
            val version = map["v"]?.jsonPrimitive?.intOrNull
            if (version != PROTOCOL_VERSION) {
                throw ProtocolError("Unsupported Remote message version.", "UNSUPPORTED_VERSION")
            }
            val id = map["id"]?.jsonPrimitive?.contentOrNull
            val type = map["type"]?.jsonPrimitive?.contentOrNull
            val timestamp = map["timestamp"]?.jsonPrimitive?.longOrNull
            if (id.isNullOrEmpty()) throw ProtocolError("Remote message id is required.")
            if (type == null || type !in REMOTE_MESSAGE_TYPES) {
                throw ProtocolError("Unknown Remote message type.")
            }
            if (timestamp == null || timestamp <= 0) {
                throw ProtocolError("Remote message timestamp is invalid.")
            }
            return RemoteMessage(type, map["payload"], id = id, timestamp = timestamp)
        }
    }
}

/** `rpc.error` 的 payload。 */
class RpcErrorPayload(
    val requestId: String,
    val code: String,
    val message: String,
    val retryable: Boolean? = null,
    val details: Any? = null,
) {
    companion object {
        fun parse(input: Any?): RpcErrorPayload {
            val map = input as? JsonObject
                ?: throw ProtocolError("rpc.error payload must be an object.")
            val requestId = map["requestId"]?.jsonPrimitive?.contentOrNull
            val code = map["code"]?.jsonPrimitive?.contentOrNull
            val message = map["message"]?.jsonPrimitive?.contentOrNull
            if (requestId.isNullOrEmpty()) throw ProtocolError("rpc.error requestId is required.")
            if (code.isNullOrEmpty()) throw ProtocolError("rpc.error code is required.")
            if (message.isNullOrEmpty()) throw ProtocolError("rpc.error message is required.")
            // 必须是布尔字面量：`1`、`"true"` 都不算 —— 数字/字符串在别的实现里
            // 会被宽松地转成 true，这里一律 fail closed。`null` 等同缺省（对齐 Dart）。
            val retryable = when (val element = map["retryable"]) {
                null, is JsonNull -> null
                is JsonPrimitive ->
                    if (!element.isString && (element.content == "true" || element.content == "false")) {
                        element.content == "true"
                    } else {
                        throw ProtocolError("rpc.error retryable must be a boolean.")
                    }
                else -> throw ProtocolError("rpc.error retryable must be a boolean.")
            }
            return RpcErrorPayload(requestId, code, message, retryable, map["details"])
        }
    }
}

/** uuid v7（时间有序）：段值与 Dart `uuidV7()` 的可见形态一致，不要求跨实现字节相同。 */
internal object UuidV7 {
    private val random = SecureRandom()

    fun next(): String {
        val millis = System.currentTimeMillis()
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        // 48 位毫秒时间戳（大端）
        bytes[0] = ((millis shr 40) and 0xff).toByte()
        bytes[1] = ((millis shr 32) and 0xff).toByte()
        bytes[2] = ((millis shr 24) and 0xff).toByte()
        bytes[3] = ((millis shr 16) and 0xff).toByte()
        bytes[4] = ((millis shr 8) and 0xff).toByte()
        bytes[5] = (millis and 0xff).toByte()
        // version 7
        bytes[6] = (((bytes[6].toInt() and 0x0f) or 0x70)).toByte()
        // variant 10xx
        bytes[8] = (((bytes[8].toInt() and 0x3f) or 0x80)).toByte()
        val buffer = StringBuilder(36)
        for (i in bytes.indices) {
            if (i == 4 || i == 6 || i == 8 || i == 10) buffer.append('-')
            buffer.append("%02x".format(bytes[i].toInt() and 0xff))
        }
        return buffer.toString()
    }
}

/** JSON 小工具：把 Dart 侧宽松的 `Object?` payload 用法收拢到一处。 */
internal object JsonValues {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    fun encodeToString(element: kotlinx.serialization.json.JsonElement): String = element.toString()

    fun encode(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    fun parseObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    /** 把 Kotlin 值转成 JsonElement；Map/List 走递归，其余走 toString/基础类型。 */
    fun toJsonElement(value: Any?): kotlinx.serialization.json.JsonElement = when (value) {
        null -> kotlinx.serialization.json.JsonNull
        is kotlinx.serialization.json.JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            for ((k, v) in value) {
                if (k == null) continue
                put(k.toString(), toJsonElement(v))
            }
        }
        is Iterable<*> -> kotlinx.serialization.json.JsonArray(value.map { toJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }
}
