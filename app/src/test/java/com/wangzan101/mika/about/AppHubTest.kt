package com.wangzan101.mika.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppHubTest {
    private val hex = "a".repeat(64)
    private val apk = "https://oss-chuangxin.thchengtay.com/ai/mika/android/mika-0.2.0-universal.apk"

    @Test
    fun readsLatestVersion() {
        val r = AppHub.parse(
            """
            {
              "appKey": "com.zl.ruyiai.yuan",
              "platform": "android",
              "channel": "official",
              "currentVersionCode": 10,
              "hasUpdate": true,
              "policy": "optional",
              "latest": {
                "versionName": "v0.2.0",
                "versionCode": 50,
                "changelog": "- 附件面板\n- 修复若干问题",
                "packageSize": 43891234,
                "sha256": "$hex",
                "publishedAt": "2026-11-13T08:30:00Z",
                "download": {"url": "$apk"}
              }
            }
            """.trimIndent()
        )!!
        assertEquals("0.2.0", r.version)
        assertEquals("0.2.0", r.name)
        assertEquals("- 附件面板\n- 修复若干问题", r.notes)
        assertEquals("2026-11-13", r.date)
        assertEquals(MikaLinks.RELEASES, r.pageUrl)
        assertEquals(apk, r.apkUrl)
        assertEquals(43891234L, r.apkSize)
        assertEquals("sha256:$hex", r.apkDigest)
        assertEquals(50, r.versionCode)
        assertFalse(r.force)
    }

    @Test
    fun forcePolicyIsKept() {
        assertTrue(AppHub.parse("""{"latest":{"versionName":"0.2.0"},"policy":"force"}""")!!.force)
        assertFalse(AppHub.parse("""{"latest":{"versionName":"0.2.0"},"policy":"optional"}""")!!.force)
    }

    /** 平台还没有已发布版本时 `latest` 为 null,当作这次没查到。 */
    @Test
    fun missingLatestMeansNoResult() {
        assertNull(AppHub.parse("""{"appKey":"com.zl.ruyiai.yuan","latest":null,"policy":"none"}"""))
        assertNull(AppHub.parse("""{"appKey":"com.zl.ruyiai.yuan"}"""))
    }

    /** 响应读不懂(网关返回 HTML、字段损坏)不能当成「已是最新」,要抛出去算失败。 */
    @Test
    fun brokenResponseThrows() {
        assertThrows(Exception::class.java) { AppHub.parse("not json") }
        assertThrows(Exception::class.java) { AppHub.parse("""{"latest":{"versionName":"  ","versionCode":50}}""") }
    }

    /** 平台存的是裸 hex,解析时补成 sha256:<hex>;已经带前缀的原样保留。 */
    @Test
    fun digestIsNormalizedToSha256Prefix() {
        assertEquals("sha256:$hex", AppHub.parse(latestJson(sha = hex))!!.apkDigest)
        assertEquals("sha256:$hex", AppHub.parse(latestJson(sha = hex.uppercase()))!!.apkDigest)
        assertEquals("sha256:$hex", AppHub.parse(latestJson(sha = "sha256:$hex"))!!.apkDigest)
    }

    @Test
    fun invalidDigestIsDropped() {
        for (sha in listOf("", "abc", "md5:" + "0".repeat(32), "g".repeat(64))) {
            assertNull(sha, AppHub.parse(latestJson(sha = sha))!!.apkDigest)
        }
    }

    @Test
    fun downloadCanLiveAtRoot() {
        val r = AppHub.parse(
            """{"latest":{"versionName":"0.2.0"},"download":{"url":"$apk"}}"""
        )!!
        assertEquals(apk, r.apkUrl)
    }

    @Test
    fun shortPublishedAtIsDropped() {
        assertNull(AppHub.parse(latestJson(publishedAt = "2026-1-1"))!!.date)
        assertEquals("2026-11-13", AppHub.parse(latestJson(publishedAt = "2026-11-13T08:30:00Z"))!!.date)
    }

    private fun latestJson(sha: String? = null, publishedAt: String? = "2026-11-13T08:30:00Z"): String = buildString {
        append("""{"latest":{"versionName":"0.2.0","versionCode":50""")
        sha?.let { append(",\"sha256\":\"$it\"") }
        publishedAt?.let { append(",\"publishedAt\":\"$it\"") }
        append("}}")
    }
}
