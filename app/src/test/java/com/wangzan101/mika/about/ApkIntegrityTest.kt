package com.wangzan101.mika.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkIntegrityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun acceptsOfficialApk() {
        ApkIntegrity.requireOfficialUrl("https://github.com/wangzan101/Mika/releases/download/v0.2.0/如愿.apk")
    }

    @Test fun rejectsUntrustedDownloads() {
        for (url in listOf(
            "http://github.com/wangzan101/Mika/releases/download/v0.2.0/如愿.apk",
            "https://github.com.evil.test/wangzan101/Mika/releases/download/v0.2.0/如愿.apk",
            "https://github.com/other/如愿/releases/download/v0.2.0/如愿.apk",
            "https://github.com/wangzan101/Mika/releases/download/../../other.apk",
            "https://github.com/wangzan101/Mika/releases/download/%2e%2e/%2E%2e/%2e%2e/%2e%2e/other/x.apk",
            "https://objects.githubusercontent.com/wangzan101/Mika/releases/download/v0.2.0/如愿.apk",
            "https://github.com/wangzan101/Mika/releases/download/v0.2.0/如愿.zip",
            "https://github.com/wangzan101/Mika/releases/tag/v0.2.0",
            "https://user:pass@github.com/wangzan101/Mika/releases/download/v0.2.0/如愿.apk",
            "https://github.com:8443/wangzan101/Mika/releases/download/v0.2.0/如愿.apk",
        )) rejects { ApkIntegrity.requireOfficialUrl(url) }
    }

    @Test fun verifiesSizeAndDigestAndRejectsPartialOrModifiedFiles() {
        val file = temp.newFile().apply { writeText("abc") }
        val digest = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        ApkIntegrity.verify(file, 3, digest)
        ApkIntegrity.verify(file, null, null) // 兼容没有附件摘要的旧发布元数据。
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
