package com.wangzan101.mika.about

import com.wangzan101.mika.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkIntegrityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun acceptsOfficialApks() {
        for (url in listOf(
            "https://oss-chuangxin.thchengtay.com/ai/mika/android/mika-0.2.0-universal.apk",
            "https://oss-chuangxin.thchengtay.com/ai/mika/android/mika-0.2.0-arm64-v8a.apk",
            // 过渡期:已发出去的老客户端仍可能拿到 GitHub 发布附件地址
            "https://github.com/wangzan101/Mika/releases/download/v0.2.0/如愿.apk",
        )) ApkIntegrity.requireOfficialUrl(url)
    }

    @Test fun rejectsUntrustedDownloads() {
        for (url in listOf(
            "http://oss-chuangxin.thchengtay.com/ai/mika/android/mika-0.2.0-universal.apk",
            "https://oss-chuangxin.thchengtay.com.evil.test/ai/mika/android/mika-0.2.0-universal.apk",
            "https://oss-evil.thchengtay.com/ai/mika/android/mika-0.2.0-universal.apk",
            "https://github.com.evil.test/wangzan101/Mika/releases/download/v0.2.0/如愿.apk",
            "https://objects.githubusercontent.com/wangzan101/Mika/releases/download/v0.2.0/如愿.apk",
            "https://oss-chuangxin.thchengtay.com/ai/mika/android/mika-0.2.0.zip",
            "https://oss-chuangxin.thchengtay.com/ai/mika/android/mika-0.2.0.apk/extra",
            "https://user:u@oss-chuangxin.thchengtay.com/ai/mika/android/mika-0.2.0-universal.apk",
            "https://oss-chuangxin.thchengtay.com:8443/ai/mika/android/mika-0.2.0-universal.apk",
        )) rejects { ApkIntegrity.requireOfficialUrl(url) }
    }

    /** 注入的发布地址是 http(本地联调)时,同主机的 http 直链也放行;其他域名照旧拒绝。 */
    @Test fun devBuildAllowsDownloadsFromConfiguredHost() {
        val base = BuildConfig.UPDATE_FEED_URL.toHttpUrl()
        Assume.assumeTrue("仅在注入 http 地址的构建里验证", base.scheme == "http")
        ApkIntegrity.requireOfficialUrl("http://${base.host}:8099/mika-0.1.49-universal.apk")
        rejects { ApkIntegrity.requireOfficialUrl("http://evil.test:8099/mika-0.1.49-universal.apk") }
    }

    @Test fun verifiesSizeAndDigestAndRejectsPartialOrModifiedFiles() {
        val file = temp.newFile().apply { writeText("abc") }
        val digest = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        ApkIntegrity.verify(file, 3, digest)
        ApkIntegrity.verify(file, null, null) // 平台没给摘要时只靠包名、版本号与签名校验。
        rejects { ApkIntegrity.verify(file, 4, digest) }
        rejects { ApkIntegrity.verify(file, 3, "sha256:bad") }
        rejects { ApkIntegrity.verify(file, 3, "md5:" + "0".repeat(64)) }
        file.writeText("abd")
        rejects { ApkIntegrity.verify(file, 3, digest) }
        file.writeText("")
        rejects { ApkIntegrity.verify(file, null, null) }
    }

    @Test fun signerMustMatchInstalledOrCarryItInHistory() {
        ApkIntegrity.requireSameSigner(setOf("a"), setOf("a"), setOf("a"))
        // 新包轮换到新证书,旧证书在签名历史里
        ApkIntegrity.requireSameSigner(setOf("a"), setOf("b"), setOf("a", "b"))
        for ((installed, archive, history) in listOf(
            Triple(setOf("a"), setOf("b"), setOf("b")),
            Triple(setOf("a"), emptySet(), emptySet()),
            Triple(emptySet(), setOf("a"), setOf("a")),
            Triple(setOf("a", "b"), setOf("a"), emptySet()),
        )) {
            try {
                ApkIntegrity.requireSameSigner(installed, archive, history)
                throw AssertionError("签名不同应拒绝: $installed / $archive")
            } catch (e: ApkRejected) {
                assertEquals(ApkRejected.Reason.Signer, e.reason)
            }
        }
    }

    @Test fun progressHandlesUnknownSizeAndLargeApks() {
        assertNull(ApkDownloadState(bytes = 10).percent)
        assertEquals(50, ApkDownloadState(bytes = 3_000_000_000, total = 6_000_000_000).percent)
        assertEquals(100, ApkDownloadState(bytes = 101, total = 100).percent)
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        } catch (e: ApkRejected) {
            assertEquals(ApkRejected.Reason.Corrupt, e.reason)
            return
        }
        throw AssertionError("应拒绝不可信或不完整的 APK")
    }
}
