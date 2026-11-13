package com.wangzan101.mika.about

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import java.net.URLEncoder

/** 如愿对外的地址都在这里:仓库、问题、检查更新用的来源。换地址只改这一处。 */
object MikaLinks {
    const val REPO = "https://github.com/wangzan101/Mika"
    const val REPO_LABEL = "github.com/wangzan101/如愿"
    const val README = "$REPO#readme"
    const val RELEASES = "$REPO/releases"
    const val NEW_ISSUE = "$REPO/issues/new"
    const val X = "https://x.com/wangzan101"
    const val X_HANDLE = "@wangzan101"

    /**
     * 如愿和原来的 Mika 包共用一个仓库发布,但 tag 各带各的前缀:Mika 发布是 v0.1.x,
     * 如愿发布是 [RELEASE_TAG_PREFIX] 开头(如 yuan-v0.1.45),互不打扰。
     * 检查更新只挑带这个前缀的发布,资产命名相应是 `如愿-v<版本>.apk` 与 `如愿-v<版本>-arm64.apk`。
     */
    const val RELEASE_TAG_PREFIX = "yuan-v"

    /** 检查更新只问 GitHub 发布列表接口(不登录,每小时有次数限制),按 [RELEASE_TAG_PREFIX] 挑出如愿自己的最新发布。 */
    const val LATEST_RELEASE_API = "https://api.github.com/repos/wangzan101/Mika/releases?per_page=30"

    /** 反馈时预先填进问题模板的设备与版本信息。 */
    data class Device(
        val manufacturer: String,
        val model: String,
        val androidRelease: String,
        val sdk: Int,
        val versionName: String,
        val build: String,
    )

    /** 预先填好的问题正文:机型、安卓版本、如愿版本三行,下面留空几段给用户写。 */
    fun issueBody(d: Device): String {
        val model = if (d.model.startsWith(d.manufacturer, ignoreCase = true)) d.model else "${d.manufacturer} ${d.model}"
        return buildString {
            append(tr(R.string.issue_body, model.trim(), d.androidRelease, d.sdk, d.versionName, d.build))
        }
    }

    /** 新建问题的地址,标题留空、正文预先填好;参数按 URL 编码(空格写成 %20)。 */
    fun issueUrl(d: Device): String = "$NEW_ISSUE?title=&body=${encode(issueBody(d))}"

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
