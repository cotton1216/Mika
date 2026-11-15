package com.wangzan101.mika.about

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 读自建发布平台(apphub)的检查更新响应:
 * `GET /api/v1/update/check?appKey=&platform=android&channel=&currentVersionCode=`。
 *
 * 只取 `latest` 里的版本元数据;有没有新版本由 [UpdateChecker] 用 versionCode 判断,
 * 这样平台返回 `hasUpdate:false` 时也不会留下上一次的旧红点。
 *
 * `latest` 为空表示平台还没有已发布版本,返回 null(不是查询失败);响应读不懂时抛异常,
 * 由调用方按查询失败处理,避免把「网关返回了一页 HTML」当成「已是最新」。
 */
internal object AppHub {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): ReleaseInfo? {
        val root = json.parseToJsonElement(text).jsonObject
        val latest = root.obj("latest") ?: return null
        val version = latest.str("versionName")?.takeIf { it.isNotBlank() }
            ?: error("latest.versionName is missing")
        // 直链既可能在 latest.download 里,也可能在根上;两处取到哪个算哪个。
        val download = latest.obj("download") ?: root.obj("download")
        return ReleaseInfo(
            version = AppVersion.normalize(version),
            name = AppVersion.normalize(version),
            notes = latest.str("changelog").orEmpty().trim(),
            date = latest.str("publishedAt")?.take(10)?.takeIf { it.length == 10 },
            pageUrl = MikaLinks.RELEASES,
            apkUrl = download?.str("url"),
            apkSize = latest.strOrNumber("packageSize")?.toLongOrNull(),
            apkDigest = digest(latest.str("sha256")),
            versionCode = latest.strOrNumber("versionCode")?.toIntOrNull(),
            force = root.str("policy") == "force",
        )
    }

    /** 平台存的是裸 hex,补成 `sha256:<hex>`,和下载后校验的口径一致;已带前缀的原样保留。 */
    private fun digest(raw: String?): String? {
        val hex = raw?.trim()?.substringAfter(':')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return if (hex.matches(Regex("[0-9a-f]{64}"))) "sha256:$hex" else null
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.strOrNumber(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
