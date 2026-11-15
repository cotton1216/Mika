package com.wangzan101.mika.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {
    private val github = """
        {
          "tag_name": "v0.2.0",
          "name": "如愿 0.2.0",
          "html_url": "https://github.com/wangzan101/Mika/releases/tag/v0.2.0",
          "published_at": "2026-11-05T08:30:00Z",
          "prerelease": false,
          "body": "### 新增\r\n- 附件面板\r\n",
          "assets": [
            {"name": "mika-0.2.0-gpl-sources.tar.gz", "browser_download_url": "https://github.com/x/src.tar.gz"},
            {"name": "mika-0.2.0.apk", "browser_download_url": "https://github.com/x/mika-0.2.0.apk", "size": 12345, "digest": "sha256:abc"}
          ]
        }
    """.trimIndent()

    @Test
    fun readsGitHubRelease() {
        val r = Releases.fromGitHub(github)
        assertEquals("0.2.0", r.version)
        assertEquals("如愿 0.2.0", r.name)
        assertEquals("2026-11-05", r.date)
        assertEquals("https://github.com/wangzan101/Mika/releases/tag/v0.2.0", r.pageUrl)
        assertEquals("https://github.com/x/mika-0.2.0.apk", r.apkUrl)
        assertEquals(r.apkUrl, r.downloadUrl)
        assertEquals(12345L, r.apkSize)
        assertEquals("sha256:abc", r.apkDigest)
        assertTrue(r.notes.contains("附件面板"))
    }

    @Test
    fun gitHubReleaseWithoutApkOrBody() {
        val r = Releases.fromGitHub("""{"tag_name":"0.3.0","name":"","body":null,"html_url":"https://github.com/wangzan101/如愿/releases/tag/0.3.0","assets":[]}""")
        assertEquals("0.3.0", r.version)
        assertEquals("0.3.0", r.name)
        assertEquals("", r.notes)
        assertNull(r.apkUrl)
        assertNull(r.apkSize)
        assertNull(r.apkDigest)
        assertNull(r.date)
        assertEquals(r.pageUrl, r.downloadUrl)
    }

    @Test(expected = Exception::class)
    fun gitHubErrorBodyIsRejected() {
        Releases.fromGitHub("""{"message":"API rate limit exceeded","documentation_url":"https://docs.github.com"}""")
    }

    @Test
    fun storedReleaseRoundTrips() {
        val r = Releases.fromGitHub(github)
        assertEquals(r, Releases.fromJson(Releases.toJson(r)))
        val bare = r.copy(date = null, apkUrl = null)
        assertEquals(bare, Releases.fromJson(Releases.toJson(bare)))
    }

    private val split = """
        {
          "tag_name": "v0.1.40",
          "html_url": "https://github.com/wangzan101/Mika/releases/tag/v0.1.40",
          "assets": [
            {"name": "如愿-v0.1.40-arm64.apk", "browser_download_url": "https://github.com/x/如愿-v0.1.40-arm64.apk", "size": 50, "digest": "sha256:arm"},
            {"name": "如愿-v0.1.40.apk", "browser_download_url": "https://github.com/x/如愿-v0.1.40.apk", "size": 80, "digest": "sha256:all"}
          ]
        }
    """.trimIndent()

    @Test
    fun arm64DevicePicksTheArm64Apk() {
        val r = Releases.fromGitHub(split, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
        assertEquals("https://github.com/x/如愿-v0.1.40-arm64.apk", r.apkUrl)
        assertEquals(50L, r.apkSize)
        assertEquals("sha256:arm", r.apkDigest)
    }

    @Test
    fun otherDevicesPickTheUniversalApk() {
        for (abis in listOf(listOf("x86_64", "x86"), listOf("armeabi-v7a", "armeabi"), emptyList())) {
            val r = Releases.fromGitHub(split, abis)
            assertEquals(abis.toString(), "https://github.com/x/如愿-v0.1.40.apk", r.apkUrl)
            assertEquals("sha256:all", r.apkDigest)
        }
    }

    @Test
    fun archApkForAnotherDeviceIsNotOffered() {
        val only = """{"tag_name":"v0.1.40","html_url":"https://github.com/wangzan101/如愿/releases/tag/v0.1.40",
            "assets":[{"name":"如愿-v0.1.40-arm64.apk","browser_download_url":"https://github.com/x/如愿-v0.1.40-arm64.apk"}]}"""
        val r = Releases.fromGitHub(only, listOf("x86_64", "x86"))
        assertNull(r.apkUrl)
        assertEquals(r.pageUrl, r.downloadUrl)
        assertEquals("https://github.com/x/如愿-v0.1.40-arm64.apk", Releases.fromGitHub(only, listOf("arm64-v8a")).apkUrl)
    }

    /** 发布列表:只看带如愿前缀的 tag,原 Mika 包的 v0.1.x、预发布与草稿都不参与;挑版本号最新的。 */
    @Test
    fun latestPicksTheNewestRuyiTagIgnoringMikaTagsPreReleasesAndDrafts() {
        val list = """
            [
              {"tag_name":"v0.2.1","html_url":"https://github.com/wangzan101/Mika/releases/tag/v0.2.1","assets":[]},
              {"tag_name":"yuan-v0.1.46","prerelease":true,"html_url":"https://github.com/x/yuan-v0.1.46","assets":[]},
              {"tag_name":"yuan-v0.1.47","draft":true,"html_url":"https://github.com/x/yuan-v0.1.47","assets":[]},
              {"tag_name":"yuan-v0.1.44","html_url":"https://github.com/x/yuan-v0.1.44","assets":[]},
              {"tag_name":"yuan-v0.1.45","name":"如愿 0.1.45",
               "html_url":"https://github.com/wangzan101/Mika/releases/tag/yuan-v0.1.45",
               "assets":[{"name":"如愿-v0.1.45-arm64.apk","browser_download_url":"https://github.com/x/如愿-v0.1.45-arm64.apk","size":50,"digest":"sha256:arm"}]}
            ]
        """.trimIndent()
        val r = Releases.latest(list, listOf("arm64-v8a"))
        assertEquals("0.1.45", r.version)
        assertEquals("https://github.com/x/如愿-v0.1.45-arm64.apk", r.apkUrl)
    }

    @Test(expected = Exception::class)
    fun latestWithoutARuyiReleaseFails() {
        Releases.latest("""[{"tag_name":"v0.2.1","assets":[]}]""")
    }
}
