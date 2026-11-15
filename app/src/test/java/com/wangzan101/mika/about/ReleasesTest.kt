package com.wangzan101.mika.about

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleasesTest {
    private val body = """
        {
          "policy": "force",
          "latest": {
            "versionName": "0.2.0",
            "versionCode": 50,
            "changelog": "### 新增\n- 附件面板",
            "packageSize": 12345,
            "sha256": "${"a".repeat(64)}",
            "publishedAt": "2026-11-05T08:30:00Z",
            "download": {"url": "https://oss-chuangxin.thchengtay.com/ai/mika/android/mika-0.2.0-universal.apk"}
          }
        }
    """.trimIndent()

    @Test
    fun storedReleaseRoundTrips() {
        val r = AppHub.parse(body)!!
        assertEquals(r, Releases.fromJson(Releases.toJson(r)))
    }

    @Test
    fun storedReleaseWithoutOptionalFieldsRoundTrips() {
        val r = AppHub.parse("""{"latest":{"versionName":"0.2.0"}}""")!!
        val bare = r.copy(date = null, apkUrl = null, apkSize = null, apkDigest = null, versionCode = null, force = false)
        assertEquals(bare, Releases.fromJson(Releases.toJson(bare)))
    }

    @Test
    fun unreadableStoredReleaseIsIgnored() {
        assertNull(Releases.fromJson(buildJsonObject { }))
    }

    /** 平台没给直链时,「下载」回到平台首页,不留空链接。 */
    @Test
    fun releaseWithoutApkFallsBackToPlatformPage() {
        val r = AppHub.parse("""{"latest":{"versionName":"0.2.0"}}""")!!
        assertNull(r.apkUrl)
        assertEquals(MikaLinks.RELEASES, r.downloadUrl)
    }
}
