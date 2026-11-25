package com.wangzan101.mika.about

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 把「检查更新」拉到的 JSON 读成 [ReleaseInfo]。两种来源：
 *
 * 1. 自研静态 feed（正式包）：一份托管在固定 URL 上的扁平 JSON。
 * ```
 * {
 *   "version": "0.1.51",
 *   "versionCode": 51,
 *   "publishedAt": "2026-11-16T12:00:00Z",
 *   "changelog": "新增...\n优化...\n修复...",
 *   "policy": "force",                       // 可选，本期只保留字段不拦截
 *   "download": { "url": "https://oss.../x.apk", "sha256": "<hex>", "size": 12345 }
 * }
 * ```
 *
 * 2. app-version-hub 的查更新响应（Dev 包读 as 台账的 beta 渠道）。
 * ```
 * { "hasUpdate": true, "latest": { "versionName", "versionCode", "changelog",
 *   "packageSize", "sha256", "download": { "url" } } }
 * ```
 *
 * 有没有新版本由 [UpdateChecker] 用 versionCode 判断；这里只负责读成 [ReleaseInfo]。
 * 空 feed（`{}`）表示还没有已发布版本，返回 null（不是查询失败）；读不懂时抛异常，
 * 由调用方按查询失败处理，避免把「网关返回了一页 HTML」当成「已是最新」。
 */
internal object AppHub {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): ReleaseInfo? {
        val root = json.parseToJsonElement(text).jsonObject
        // 空 feed 表示还没有已发布版本。
        if (root.isEmpty()) return null
        root.obj("latest")?.let { return fromHubItem(it) }
        val version = root.str("version")?.takeIf { it.isNotBlank() }
            ?: error("feed.version is missing")
        val download = root.obj("download")
        return ReleaseInfo(
            version = AppVersion.normalize(version),
            name = AppVersion.normalize(version),
            notes = root.str("changelog").orEmpty().trim(),
            date = root.str("publishedAt")?.take(10)?.takeIf { it.length == 10 },
            pageUrl = MikaLinks.RELEASES,
            apkUrl = download?.str("url"),
            // 兼容 size/sha256 写在 download 内或根上两种位置。
            apkSize = download?.strOrNumber("size")?.toLongOrNull()
                ?: root.strOrNumber("size")?.toLongOrNull(),
            apkDigest = digest(download?.str("sha256") ?: root.str("sha256")),
            versionCode = root.strOrNumber("versionCode")?.toIntOrNull(),
            force = root.str("policy") == "force",
        )
    }

    /** app-version-hub 的一个 latest 条目：版本号在 versionName，APK 参数在条目根上。 */
    private fun fromHubItem(item: JsonObject): ReleaseInfo {
        val version = (item.str("versionName") ?: item.str("version"))?.takeIf { it.isNotBlank() }
            ?: error("latest.versionName is missing")
        val download = item.obj("download")
        return ReleaseInfo(
            version = AppVersion.normalize(version),
            name = AppVersion.normalize(version),
            notes = item.str("changelog").orEmpty().trim(),
            date = item.str("publishedAt")?.take(10)?.takeIf { it.length == 10 },
            pageUrl = MikaLinks.RELEASES,
            apkUrl = download?.str("url") ?: item.str("downloadUrl"),
            apkSize = item.strOrNumber("packageSize")?.toLongOrNull(),
            apkDigest = digest(item.str("sha256")),
            versionCode = item.strOrNumber("versionCode")?.toIntOrNull(),
            force = item.str("policy") == "force" || item.bool("forceUpdate"),
        )
    }

    /** 平台存的是裸 hex，补成 `sha256:<hex>`，和下载后校验的口径一致；已带前缀的原样保留。 */
    private fun digest(raw: String?): String? {
        val hex = raw?.trim()?.substringAfter(':')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return if (hex.matches(Regex("[0-9a-f]{64}"))) "sha256:$hex" else null
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.strOrNumber(key: String): String? = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.content == "true"
}
