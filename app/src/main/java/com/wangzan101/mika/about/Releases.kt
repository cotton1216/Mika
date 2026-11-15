package com.wangzan101.mika.about

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 一个发布的版本:版本号(不带 v)、标题、更新说明(Markdown)、发布日期、发布页、APK 直链。 */
data class ReleaseInfo(
    val version: String,
    val name: String,
    /** 更新说明。 */
    val notes: String,
    /** 发布日期 yyyy-MM-dd;来源没写时为空。 */
    val date: String?,
    val pageUrl: String,
    val apkUrl: String?,
    /** APK 的字节数;接口没给时为空。 */
    val apkSize: Long? = null,
    /** APK 摘要,形如 `sha256:<hex>`;缺失时只靠包名、版本号和签名把关。 */
    val apkDigest: String? = null,
    /** 平台上的版本序号;检查更新按它比新旧,老缓存里没有。 */
    val versionCode: Int? = null,
    /** 平台要求强制更新(policy=force)。本期只保留字段,不拦截。 */
    val force: Boolean = false,
) {
    /** 「下载」用的地址:有 APK 直链就下 APK。 */
    val downloadUrl: String get() = apkUrl ?: pageUrl
}

/** 最近一次查到的版本存盘与读回:App 重开后红点还在,不用等联网。 */
object Releases {
    fun toJson(r: ReleaseInfo): JsonObject = buildJsonObject {
        put("version", r.version)
        put("name", r.name)
        put("notes", r.notes)
        r.date?.let { put("date", it) }
        put("pageUrl", r.pageUrl)
        r.apkUrl?.let { put("apkUrl", it) }
        r.apkSize?.let { put("apkSize", it) }
        r.apkDigest?.let { put("apkDigest", it) }
        r.versionCode?.let { put("versionCode", it) }
        if (r.force) put("force", true)
    }

    fun fromJson(o: JsonObject): ReleaseInfo? = runCatching {
        ReleaseInfo(
            version = o.str("version")!!, name = o.str("name") ?: o.str("version")!!, notes = o.str("notes").orEmpty(),
            date = o.str("date"), pageUrl = o.str("pageUrl") ?: MikaLinks.RELEASES, apkUrl = o.str("apkUrl"),
            apkSize = o.strOrNumber("apkSize")?.toLongOrNull(), apkDigest = o.str("apkDigest"),
            versionCode = o.strOrNumber("versionCode")?.toIntOrNull(),
            force = (o["force"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.strOrNumber(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
