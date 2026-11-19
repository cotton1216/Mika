package com.wangzan101.mika.about

import com.wangzan101.mika.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.security.MessageDigest

/** 更新包没通过校验时为什么拒绝;界面按这个给出不同的说明。 */
class ApkRejected(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** 大小、SHA-256 对不上,或者根本读不出安装包。 */
        Corrupt,
        /** 不是如意 AI,或者版本不比已装的新。 */
        NotNewer,
        /** 签名证书和已装的如意 AI 不同。 */
        Signer,
    }
}

/**
 * 下载地址只接受官方发布通道的 HTTPS 直链:OSS 下载域名(过渡期兼容 GitHub 发布附件)。
 * 之后的跳转由系统下载服务处理、我们管不到,所以下载完的文件一律再验:
 * 大小与 SHA-256(平台给了才验)、包名、版本号、签名证书。
 */
internal object ApkIntegrity {
    /** 按主机放行:换桶/换路径不用跟着改,真正的防篡改靠下载后的摘要与签名校验。 */
    private val allowedHosts = setOf("oss-chuangxin.thchengtay.com", "github.com")

    fun requireOfficialUrl(url: String) {
        val parsed = url.toHttpUrl()
        require((parsed.isHttps && parsed.host in allowedHosts && parsed.port == 443) || devHostMatches(parsed))
        require(parsed.username.isEmpty() && parsed.password.isEmpty())
        require(parsed.encodedPath.endsWith(".apk", ignoreCase = true))
    }

    /**
     * 开发/测试构建注入的是 http 发布地址时,允许从同一主机取包(本地联调用)。
     * 线上注入 https 时不生效,信任范围仍然只有上面的白名单域名。
     */
    private fun devHostMatches(url: HttpUrl): Boolean {
        val base = BuildConfig.UPDATE_FEED_URL.toHttpUrlOrNull() ?: return false
        return base.scheme == "http" && url.scheme == "http" && url.host == base.host
    }

    /** 给了摘要就必须是 sha256 且对得上;别的写法也算对不上,不放过。 */
    fun verify(file: File, size: Long?, digest: String?) {
        corruptUnless(file.isFile && file.length() > 0)
        if (size != null) corruptUnless(file.length() == size)
        if (digest != null) {
            corruptUnless(digest.matches(Regex("sha256:[0-9a-fA-F]{64}")))
            corruptUnless(sha256(file).equals(digest.substringAfter(':'), ignoreCase = true))
        }
    }

    /**
     * 新包的签名证书(各证书 SHA-256)要和已装的一致;新包轮换过签名时,已装的证书要在它的签名历史里。
     * 系统安装器也会拦,这里提前拦下是为了给出说得清的提示。
     */
    fun requireSameSigner(installed: Set<String>, archive: Set<String>, archiveHistory: Set<String>) {
        if (installed.isEmpty() || archive.isEmpty()) throw ApkRejected(ApkRejected.Reason.Signer)
        if (archive != installed && !archiveHistory.containsAll(installed)) throw ApkRejected(ApkRejected.Reason.Signer)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    private fun sha256(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sha.update(buffer, 0, count)
            }
        }
        return sha.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun corruptUnless(ok: Boolean) {
        if (!ok) throw ApkRejected(ApkRejected.Reason.Corrupt)
    }
}
