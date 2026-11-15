package com.xxt.zl.ai.ruyiremote.util

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * 无补位 base64url（RFC 4648 §5）——线上每个密钥、握手消息、密文的编码形态。
 *
 * 对应 Dart `util/bytes.dart` 的 `toBase64Url`。
 */
fun toBase64Url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/**
 * 解码 base64url，带不带补位都收。
 *
 * 不做规范性检查（那由 `isCanonicalBase64Url` 负责）——与 Dart 一致：
 * `fromBase64Url` 只负责解，长度非法时抛 [IllegalArgumentException]。
 */
fun fromBase64Url(value: String): ByteArray {
    val pad = (4 - value.length % 4) % 4
    require(pad != 3) { "invalid base64url length" }
    return Base64.getUrlDecoder().decode(value + "=".repeat(pad))
}

/**
 * Dart `asInt` 的等价物：整数原样返回，整值浮点截断，其余 null。
 *
 * 线上协议里 `counter`/`epoch`/`step` 都是整数，但 JSON 里写成 `1.0` 的实现也存在
 * （JS 侧 `1.0` 序列化回来就是 `1`，这里只是防御）。
 */
fun JsonObject.intValue(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    primitive.longOrNull?.let { return it }
    return primitive.contentOrNull?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it == Math.floor(it) }
        ?.toLong()
}

/** Dart `asString` 的等价物：只接受 JSON 字符串。 */
fun JsonObject.stringValue(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.contentOrNull
}
