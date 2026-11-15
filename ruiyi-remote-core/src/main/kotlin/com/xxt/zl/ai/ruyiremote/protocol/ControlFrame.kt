package com.xxt.zl.ai.ruyiremote.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** 控制通道帧类型（ds-harness-remote protocol v1）。 */
val CONTROL_FRAME_TYPES = setOf(
    "hello",
    "hello.ack",
    "connect.request",
    "connect.incoming",
    "connect.accepted",
    "connect.rejected",
    "secure.handshake",
    "relay",
    "signal.offer",
    "signal.answer",
    "signal.ice",
    "transport.selected",
    "ping",
    "pong",
    "error",
)

/** `lan | p2p | turn | relay`，按优先级排列。 */
val REMOTE_TRANSPORTS = listOf("lan", "p2p", "turn", "relay")

/** 非 relay 控制帧的 UTF-8 JSON 上限。 */
const val MAX_CONTROL_FRAME_BYTES = 64 * 1024

/** `relay` 控制帧与 Relay 数据帧的 UTF-8 JSON 上限。 */
const val MAX_RELAY_FRAME_BYTES = 1024 * 1024

/** 能力列表最多条目数（`hello`、`hello.ack`、`harness.transport.describe`）。 */
const val MAX_REMOTE_CAPABILITY_ENTRIES = 128

private const val MAX_PROTOCOL_LIST_ENTRIES = 16

/** JS 安全整数上限；超过它的值在其它实现里会失真，一律拒绝。 */
const val MAX_SAFE_INTEGER = 9007199254740991L

/** 规范 32 字节 X25519 公钥编码：恰好 43 个字符。 */
val IDENTITY_KEY_PATTERN = Regex("^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$")

private const val BASE64URL_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
private val BASE64URL_PATTERN = Regex("^[A-Za-z0-9_-]+$")

/**
 * 非空、无补位的 base64url，且末尾未用到的位为 0 —— 保证每个字节串只有一种线上形态
 * （握手数据、Relay 密文都靠这个性质做等价比较）。
 */
fun isCanonicalBase64Url(value: String): Boolean {
    if (!BASE64URL_PATTERN.matches(value)) return false
    val remainder = value.length % 4
    if (remainder == 1) return false
    if (remainder == 0) return true
    val last = BASE64URL_ALPHABET.indexOf(value[value.length - 1])
    return if (remainder == 2) last and 15 == 0 else last and 3 == 0
}

/** 协商后的每 socket 帧上限（`hello.ack`）；两者都只能收窄 v1 的默认值。 */
class ControlFrameLimits(
    val maxControlFrameBytes: Int? = null,
    val maxRelayFrameBytes: Int? = null,
)

/**
 * 一帧控制消息。对应 Dart `protocol/control.dart` 的 `ControlFrame`。
 *
 * 注意一处刻意保留的实现差异：Dart 的 `decodeWireJson` 会拒绝**重复键**的 JSON，
 * kotlinx.serialization 取后者。这不影响安全（校验的是解析后的最终值），
 * 只影响「线上形态唯一」这条更强的性质；跨实现互操作时不会因此失败。
 */
class ControlFrame(
    val type: String,
    val payload: JsonObject,
    id: String? = null,
    timestamp: Long? = null,
) {
    val id: String = id ?: UuidV7.next()
    val timestamp: Long = timestamp ?: System.currentTimeMillis()

    fun toJson(): JsonObject = buildJsonObject {
        put("v", PROTOCOL_VERSION)
        put("id", this@ControlFrame.id)
        put("type", type)
        put("timestamp", this@ControlFrame.timestamp)
        put("payload", payload)
    }
}

/**
 * 按上游 `parseControlFrame` 校验：严格信封、已知类型、类型化的 payload。
 * payload 里的未知字段容忍（向前兼容），信封里的未知字段不容忍。
 */
fun parseControlFrame(input: JsonElement?): ControlFrame {
    val map = input as? JsonObject ?: throw ProtocolError("Control frame must be a JSON object.")
    val allowed = setOf("v", "id", "type", "timestamp", "payload")
    for (key in map.keys) {
        if (key !in allowed) throw ProtocolError("Unexpected Control envelope field \"$key\".")
    }
    if (map.int("v") != PROTOCOL_VERSION) {
        throw ProtocolError("Unsupported Control protocol version.", "UNSUPPORTED_VERSION")
    }
    val id = map.str("id")
    if (id.isNullOrEmpty()) throw ProtocolError("Control frame id is required.")
    val type = map.str("type")
    if (type == null || type !in CONTROL_FRAME_TYPES) {
        throw ProtocolError("Unknown Control frame type.")
    }
    val timestamp = map["timestamp"]?.let { it as? JsonPrimitive }?.takeIf { !it.isString }?.longOrNull
    if (timestamp == null || timestamp <= 0) {
        throw ProtocolError("Control frame timestamp must be a positive integer.")
    }
    if (!map.containsKey("payload")) throw ProtocolError("Control frame payload is required.")
    val payload = map["payload"] as? JsonObject
        ?: throw ProtocolError("$type payload must be an object.")
    validatePayload(type, payload)
    return ControlFrame(type, payload, id = id, timestamp = timestamp)
}

/** 校验并编码 [frame]，同时遵守（协商后的）字节上限。 */
fun encodeControlFrame(frame: ControlFrame, limits: ControlFrameLimits = ControlFrameLimits()): String {
    val parsed = parseControlFrame(frame.toJson())
    val encoded = parsed.toJson().toString()
    assertSize(encoded.toByteArray(Charsets.UTF_8).size, parsed.type, limits)
    return encoded
}

/** 解码一条控制文本帧，先查上限再解析。 */
fun decodeControlFrame(data: String, limits: ControlFrameLimits = ControlFrameLimits()): ControlFrame {
    val bytes = data.toByteArray(Charsets.UTF_8).size
    if (bytes > MAX_RELAY_FRAME_BYTES) {
        throw ProtocolError("Control frame exceeds the Relay frame limit.", "FRAME_TOO_LARGE")
    }
    val json = runCatching { JsonValues.json.parseToJsonElement(data) }
        .getOrElse { throw ProtocolError("Control frame is not JSON.") }
    val frame = parseControlFrame(json)
    assertSize(bytes, frame.type, limits)
    return frame
}

/**
 * 编码一条独立的 Relay 数据 WebSocket 帧。信封与控制帧相同，`type` 自由；
 * 每帧都计入 Relay 上限。
 */
fun encodeRelayDataFrame(frame: ControlFrame, limits: ControlFrameLimits = ControlFrameLimits()): String {
    val parsed = parseRelayDataFrame(frame.toJson())
    val encoded = parsed.toJson().toString()
    assertSize(encoded.toByteArray(Charsets.UTF_8).size, "relay", limits)
    return encoded
}

/** 解码一条 Relay 数据文本帧（`relay.ready`、`relay`、`relay.detach`、`ping`…）。 */
fun decodeRelayDataFrame(data: String, limits: ControlFrameLimits = ControlFrameLimits()): ControlFrame {
    assertSize(data.toByteArray(Charsets.UTF_8).size, "relay", limits)
    val json = runCatching { JsonValues.json.parseToJsonElement(data) }
        .getOrElse { throw ProtocolError("Invalid Relay data frame.") }
    return parseRelayDataFrame(json)
}

private fun parseRelayDataFrame(input: JsonElement?): ControlFrame {
    val map = input as? JsonObject
    val id = map?.str("id")
    val type = map?.str("type")
    val timestamp = map?.get("timestamp")?.let { it as? JsonPrimitive }?.takeIf { !it.isString }?.longOrNull
    val payload = map?.get("payload") as? JsonObject
    if (map == null ||
        map.size != 5 ||
        map.int("v") != PROTOCOL_VERSION ||
        id.isNullOrEmpty() ||
        type.isNullOrEmpty() ||
        timestamp == null ||
        timestamp <= 0 ||
        timestamp > MAX_SAFE_INTEGER ||
        payload == null
    ) {
        throw ProtocolError("Invalid Relay data frame.")
    }
    return ControlFrame(type, payload, id = id, timestamp = timestamp)
}

private fun assertSize(bytes: Int, type: String, limits: ControlFrameLimits) {
    val relay = type == "relay"
    val configured = if (relay) limits.maxRelayFrameBytes else limits.maxControlFrameBytes
    val maximum = if (relay) MAX_RELAY_FRAME_BYTES else MAX_CONTROL_FRAME_BYTES
    val limit = if (configured == null) maximum else minOf(configured, maximum)
    if (limit <= 0) throw ProtocolError("Control frame limit is invalid.")
    if (bytes > limit) {
        throw ProtocolError("${if (relay) "Relay" else "Control"} frame exceeds its limit.", "FRAME_TOO_LARGE")
    }
}

// ---------------------------------------------------------------------------
// 协商

/** 双方集合里最高的版本，没有则 null。 */
fun selectProtocolVersion(offered: List<Int>, supported: List<Int> = listOf(PROTOCOL_VERSION)): Int? =
    offered.filter { it in supported }.maxOrNull()

/** [supported] 过滤到 [offered]，按 [supported] 的顺序。 */
fun selectCapabilities(offered: List<String>, supported: List<String>): List<String> {
    val offeredSet = offered.toSet()
    return supported.distinct().filter { it in offeredSet }
}

/**
 * 校验 Server 在 `hello.ack.capabilities` 里的选择。
 * 老版本 Server 省略该字段，隐含 `transport.relay`。
 */
fun acceptNegotiatedCapabilities(offered: List<String>, negotiated: List<String>?): List<String> {
    val accepted = negotiated ?: listOf("transport.relay")
    if (selectCapabilities(accepted, offered).size != accepted.size) {
        throw ProtocolError("Server selected a capability that the peer did not offer.")
    }
    return accepted
}

// ---------------------------------------------------------------------------
// Payload schema

private fun validatePayload(type: String, p: JsonObject) {
    when (type) {
        "hello" -> {
            enumValue(p, "role", listOf("host", "client"))
            nonEmpty(p, "deviceId")
            nonEmpty(p, "accessToken")
            val protocols = list(p, "protocols")
            if (protocols.isEmpty() ||
                protocols.size > MAX_PROTOCOL_LIST_ENTRIES ||
                protocols.any { item ->
                    val v = (item as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                    v == null || v < 0 || v > MAX_SAFE_INTEGER
                } ||
                protocols.map { (it as JsonPrimitive).content }.toSet().size != protocols.size
            ) {
                throw ProtocolError("hello.protocols is invalid.")
            }
            capabilities(p, required = true)
            optionalString(p, "clientVersion")
            optionalString(p, "harnessVersion")
        }

        "hello.ack" -> {
            if (p.int("protocol") != PROTOCOL_VERSION) {
                throw ProtocolError("hello.ack selected an unsupported protocol.", "UNSUPPORTED_VERSION")
            }
            nonEmpty(p, "serverVersion")
            nonEmpty(p, "connectionSessionId")
            positiveInt(p, "heartbeatIntervalMs")
            positiveInt(p, "maxControlFrameBytes", max = MAX_CONTROL_FRAME_BYTES.toLong())
            positiveInt(p, "maxRelayFrameBytes", max = MAX_RELAY_FRAME_BYTES.toLong())
            capabilities(p, required = false)
            if (p.containsKey("webrtcEnabled") && p.boolean("webrtcEnabled") == null) {
                throw ProtocolError("hello.ack.webrtcEnabled must be a boolean.")
            }
            if (p.containsKey("webrtcFallbackTimeoutMs")) positiveInt(p, "webrtcFallbackTimeoutMs")
        }

        "connect.request" -> {
            nonEmpty(p, "hostDeviceId")
            transports(p)
        }

        "connect.incoming" -> {
            nonEmpty(p, "connectionId")
            nonEmpty(p, "clientDeviceId")
            val key = p.str("clientIdentityKey")
            if (key == null || !IDENTITY_KEY_PATTERN.matches(key)) {
                throw ProtocolError("connect.incoming.clientIdentityKey is not a canonical X25519 key.")
            }
            if (p.str("authorization") != "account") {
                throw ProtocolError("connect.incoming.authorization must be \"account\".")
            }
            transports(p)
            relayAssignment(p)
        }

        "connect.accepted" -> {
            nonEmpty(p, "connectionId")
            relayAssignment(p)
        }

        "connect.rejected" -> {
            nonEmpty(p, "connectionId")
            optionalString(p, "code")
            optionalString(p, "message")
        }

        "secure.handshake" -> {
            nonEmpty(p, "connectionId")
            nonEmpty(p, "targetDeviceId")
            val step = p["step"]?.let { it as? JsonPrimitive }?.takeIf { !it.isString }?.intOrNull
            if (step != 1 && step != 2) throw ProtocolError("secure.handshake.step must be 1 or 2.")
            base64Url(p, "data")
        }

        "relay" -> {
            nonEmpty(p, "connectionId")
            nonEmpty(p, "targetDeviceId")
            val counter = p["counter"]?.let { it as? JsonPrimitive }?.takeIf { !it.isString }?.longOrNull
            if (counter == null || counter < 0 || counter > MAX_SAFE_INTEGER) {
                throw ProtocolError("relay.counter must be a non-negative safe integer.")
            }
            base64Url(p, "ciphertext")
        }

        "signal.offer", "signal.answer" -> {
            nonEmpty(p, "connectionId")
            nonEmpty(p, "targetDeviceId")
            nonEmpty(p, "sdp")
        }

        "signal.ice" -> {
            nonEmpty(p, "connectionId")
            nonEmpty(p, "targetDeviceId")
            if (p["candidate"] !is JsonObject) {
                throw ProtocolError("signal.ice.candidate must be an object.")
            }
        }

        "transport.selected" -> {
            nonEmpty(p, "connectionId")
            nonEmpty(p, "targetDeviceId")
            enumValue(p, "transport", REMOTE_TRANSPORTS)
        }

        "ping", "pong" -> nonEmpty(p, "nonce")

        "error" -> {
            nonEmpty(p, "code")
            nonEmpty(p, "message")
            if (p.containsKey("retryable") && p.boolean("retryable") == null) {
                throw ProtocolError("error.retryable must be a boolean.")
            }
            if (p.containsKey("connectionId")) nonEmpty(p, "connectionId")
        }
    }
}

private fun nonEmpty(p: JsonObject, key: String) {
    val value = p.str(key)
    if (value.isNullOrEmpty()) throw ProtocolError("$key must be a non-empty string.")
}

private fun optionalString(p: JsonObject, key: String) {
    if (p.containsKey(key) && p[key] !is JsonPrimitive) {
        throw ProtocolError("$key must be a string.")
    }
    if (p.containsKey(key) && (p[key] as JsonPrimitive).isString.not() && p[key] != JsonNull) {
        throw ProtocolError("$key must be a string.")
    }
}

private fun enumValue(p: JsonObject, key: String, values: List<String>) {
    if (p.str(key) !in values) throw ProtocolError("$key is not one of $values.")
}

private fun positiveInt(p: JsonObject, key: String, max: Long? = null) {
    val value = p[key]?.let { it as? JsonPrimitive }?.takeIf { !it.isString }?.longOrNull
    if (value == null || value <= 0 || value > MAX_SAFE_INTEGER || (max != null && value > max)) {
        throw ProtocolError("$key must be a positive integer${max?.let { " ≤ $it" } ?: ""}.")
    }
}

private fun list(p: JsonObject, key: String): List<JsonElement> {
    val value = p[key]
    if (value !is JsonArray) throw ProtocolError("$key must be an array.")
    return value
}

private fun capabilities(p: JsonObject, required: Boolean) {
    if (!p.containsKey("capabilities")) {
        if (required) throw ProtocolError("capabilities is required.")
        return
    }
    val values = list(p, "capabilities")
    if (values.size > MAX_REMOTE_CAPABILITY_ENTRIES ||
        values.any { it !is JsonPrimitive || (it as JsonPrimitive).isString.not() || it.content.isEmpty() } ||
        values.map { (it as JsonPrimitive).content }.toSet().size != values.size
    ) {
        throw ProtocolError(
            "capabilities must be at most $MAX_REMOTE_CAPABILITY_ENTRIES unique non-empty strings.",
        )
    }
}

private fun transports(p: JsonObject) {
    val values = list(p, "preferredTransports")
    if (values.isEmpty() ||
        values.size > REMOTE_TRANSPORTS.size ||
        values.any { (it as? JsonPrimitive)?.contentOrNull !in REMOTE_TRANSPORTS }
    ) {
        throw ProtocolError("preferredTransports is invalid.")
    }
}

private fun base64Url(p: JsonObject, key: String) {
    val value = p.str(key)
    if (value == null || !isCanonicalBase64Url(value)) {
        throw ProtocolError("$key must be canonical unpadded base64url.")
    }
}

/** 可选的独立 Relay 分派（`transport.relay.attach`）。 */
private fun relayAssignment(p: JsonObject) {
    if (p.containsKey("relayEndpoint")) {
        val endpoint = p.str("relayEndpoint")
        if (endpoint == null || !endpoint.contains("://")) {
            throw ProtocolError("relayEndpoint must be a URL.")
        }
    }
    if (p.containsKey("relayTicket")) nonEmpty(p, "relayTicket")
    if (p.containsKey("relayNodeId")) nonEmpty(p, "relayNodeId")
    if (p.containsKey("relayEpoch")) {
        val epoch = p["relayEpoch"]?.let { it as? JsonPrimitive }?.takeIf { !it.isString }?.longOrNull
        if (epoch == null || epoch < 0) throw ProtocolError("relayEpoch must be a non-negative integer.")
    }
}

// ---------------------------------------------------------------------------
// JSON 取值小工具

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

internal fun JsonObject.boolean(key: String): Boolean? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return when (primitive.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}
