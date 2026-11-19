package com.wangzan101.mika.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppHubTest {
    private val hex = "a".repeat(64)
    private val apk = "https://oss-chuangxin.thchengtay.com/ai/ruyi/android/ruyi-0.2.0-universal.apk"

    @Test
    fun readsLatestVersion() {
        val r = AppHub.parse(
            """
            {
              "version": "v0.2.0",
              "versionCode": 50,
              "changelog": "- 附件面板\n- 修复若干问题",
              "publishedAt": "2026-11-13T08:30:00Z",
              "policy": "optional",
              "download": {"url": "$apk", "size": 43891234, "sha256": "$hex"}
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
        assertTrue(AppHub.parse("""{"version":"0.2.0","policy":"force"}""")!!.force)
        assertFalse(AppHub.parse("""{"version":"0.2.0","policy":"optional"}""")!!.force)
    }

    /** feed 还没有已发布版本时是空对象,当作这次没查到。 */
    @Test
    fun missingFeedMeansNoResult() {
        // 空 feed(还没发布过版本)返回 null;非空但缺 version 是坏 feed,应抛异常(见 brokenFeedThrows)。
        assertNull(AppHub.parse("""{}"""))
        assertNull(AppHub.parse("""  {}  """))
        assertThrows(Exception::class.java) { AppHub.parse("""{"versionCode":0}""") }
    }

    /** feed 读不懂(网关返回 HTML、字段损坏)不能当成「已是最新」,要抛出去算失败。 */
    @Test
    fun brokenFeedThrows() {
        assertThrows(Exception::class.java) { AppHub.parse("not json") }
        assertThrows(Exception::class.java) { AppHub.parse("""{"version":"  ","versionCode":50}""") }
    }

    /** 平台存的是裸 hex,解析时补成 sha256:<hex>;已经带前缀的原样保留。 */
    @Test
    fun digestIsNormalizedToSha256Prefix() {
        assertEquals("sha256:$hex", AppHub.parse(feedJson(sha = hex))!!.apkDigest)
        assertEquals("sha256:$hex", AppHub.parse(feedJson(sha = hex.uppercase()))!!.apkDigest)
        assertEquals("sha256:$hex", AppHub.parse(feedJson(sha = "sha256:$hex"))!!.apkDigest)
    }

    @Test
    fun invalidDigestIsDropped() {
        for (sha in listOf("", "abc", "md5:" + "0".repeat(32), "g".repeat(64))) {
            assertNull(sha, AppHub.parse(feedJson(sha = sha))!!.apkDigest)
        }
    }

    /** size/sha256 既可写在 download 内,也可写在 feed 根上,两处取到哪个算哪个。 */
    @Test
    fun sizeAndDigestCanLiveAtRoot() {
        val r = AppHub.parse(
            """{"version":"0.2.0","sha256":"$hex","size":43891234,"download":{"url":"$apk"}}"""
        )!!
        assertEquals(apk, r.apkUrl)
        assertEquals(43891234L, r.apkSize)
        assertEquals("sha256:$hex", r.apkDigest)
    }

    @Test
    fun shortPublishedAtIsDropped() {
        assertNull(AppHub.parse(feedJson(publishedAt = "2026-1-1"))!!.date)
        assertEquals("2026-11-13", AppHub.parse(feedJson(publishedAt = "2026-11-13T08:30:00Z"))!!.date)
    }

    private fun feedJson(sha: String? = null, publishedAt: String? = "2026-11-13T08:30:00Z"): String = buildString {
        append("""{"version":"0.2.0","versionCode":50""")
        sha?.let { append(",\"sha256\":\"$it\"") }
        publishedAt?.let { append(",\"publishedAt\":\"$it\"") }
        append("}")
    }
}
