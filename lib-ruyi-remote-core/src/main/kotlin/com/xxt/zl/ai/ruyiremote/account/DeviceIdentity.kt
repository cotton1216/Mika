package com.xxt.zl.ai.ruyiremote.account

import com.xxt.zl.ai.ruyiremote.crypto.X25519
import com.xxt.zl.ai.ruyiremote.util.fromBase64Url
import com.xxt.zl.ai.ruyiremote.util.toBase64Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * 规范 X25519 公钥编码：正好 43 个字符，**末位只能是 `AEIMQUYcgkosw048`**。
 *
 * 末位受限不是苛刻，是「尾部未用位必须为 0」的等价表述：32 字节 = 256 位，
 * 43 个 base64 字符承载 258 位，最后 2 位是填充。放开末位就意味着同一个公钥
 * 有 4 种写法，指纹比对会随编码抖动 —— 这类问题只在真机上偶发，极难查。
 */
fun isIdentityKey(value: String): Boolean =
    Regex("^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$").matches(value)

/** 32 字节公钥 → 12 位十六进制指纹（`A1B2C3D4E5F6`），供人眼核对。 */
fun identityFingerprint(publicKeyBase64Url: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(fromBase64Url(publicKeyBase64Url))
    return digest.take(6).joinToString("") { "%02X".format(it) }
}

/** 指纹按 4 位分组，方便念出来核对：`A1B2 C3D4 E5F6`。 */
fun formatFingerprint(fingerprint: String): String =
    Regex(".{1,4}").findAll(fingerprint).joinToString(" ") { it.value }

/** 定长比较，避免按字节短路泄漏前缀。 */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

private val secureRandom = SecureRandom()

/**
 * RFC 9562 UUIDv7（按时间有序），用作设备 id。
 *
 * 前 6 字节是毫秒时间戳、`version=7`、`variant=0b10`，其余随机。时间有序让
 * 服务端按 id 排序就等于按注册时间排序，也方便日志里定位。
 */
fun uuidV7(nowMs: Long = System.currentTimeMillis()): String {
    val bytes = ByteArray(16).also(secureRandom::nextBytes)
    for (i in 0 until 6) {
        bytes[i] = ((nowMs shr (8 * (5 - i))) and 0xff).toByte()
    }
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString("") { "%02x".format(it) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20)}"
}

/**
 * 本机在服务端上的身份。
 *
 * [privateKey] 是 Noise IK 的静态私钥，**永不上行**；[publicKey] 就是注册时
 * 上报的 `identityKey`，也是 Host 侧用来 pin 我们的那把钥匙。两者都按
 * 无补位 base64url 存。
 */
data class DeviceIdentity(
    val deviceId: String,
    val privateKey: String,
    val publicKey: String,
    val name: String,
    val platform: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("v", 1)
        put("deviceId", deviceId)
        put("privateKey", privateKey)
        put("publicKey", publicKey)
        put("name", name)
        put("platform", platform)
    }

    companion object {
        fun fromJson(json: JsonObject): DeviceIdentity? {
            val deviceId = json.string("deviceId") ?: return null
            val privateKey = json.string("privateKey") ?: return null
            val publicKey = json.string("publicKey") ?: return null
            val name = json.string("name") ?: return null
            val platform = json.string("platform") ?: return null
            if (!isIdentityKey(publicKey)) return null
            return DeviceIdentity(deviceId, privateKey, publicKey, name, platform)
        }

        /** 生成一把新身份。私钥原样保存（clamp 由标量乘内部完成）。 */
        fun create(name: String, platform: String): DeviceIdentity {
            val keys = X25519.generateKeyPair()
            return DeviceIdentity(
                deviceId = uuidV7(),
                privateKey = toBase64Url(keys.privateKey),
                publicKey = toBase64Url(keys.publicKey),
                name = name,
                platform = platform,
            )
        }

        /** 宽松解析：坏记录返回 null，调用方当作「没有身份」，下次登录重建。 */
        fun parse(raw: String?): DeviceIdentity? {
            if (raw.isNullOrEmpty()) return null
            return try {
                fromJson(Json.parseToJsonElement(raw) as JsonObject)
            } catch (_: Exception) {
                null
            }
        }
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
