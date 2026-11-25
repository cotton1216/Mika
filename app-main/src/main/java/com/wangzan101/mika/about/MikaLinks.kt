package com.wangzan101.mika.about

import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import java.net.URLEncoder

/** 如意 AI 对外的地址都在这里:仓库、问题、检查更新用的来源。换地址只改这一处。 */
object MikaLinks {
    const val REPO = "https://github.com/wangzan101/Mika"
    const val REPO_LABEL = "github.com/wangzan101/Mika"
    const val README = "$REPO#readme"
    const val NEW_ISSUE = "$REPO/issues/new"
    const val X = "https://x.com/wangzan101"
    const val X_HANDLE = "@wangzan101"

    /** 发布页:静态发布记录页。无 APK 直链时的兜底下载入口,不依赖任何动态服务。 */
    const val RELEASES = "$REPO/releases"

    /**
     * 检查更新用的地址(构建注入,见 build.gradle.kts 的 updateFeedUrl):
     * 正式包是静态 feed(一份描述当前最新版的 JSON);自用调试包(Dev)是 as 台账的 beta 渠道,
     * 条目结构与 feed 不同,由 [AppHub] 按响应形状分别解析。
     */
    val UPDATE_FEED: String get() = BuildConfig.UPDATE_FEED_URL

    /** 反馈时预先填进问题模板的设备与版本信息。 */
    data class Device(
        val manufacturer: String,
        val model: String,
        val androidRelease: String,
        val sdk: Int,
        val versionName: String,
        val build: String,
    )

    /** 预先填好的问题正文:机型、安卓版本、如意 AI 版本三行,下面留空几段给用户写。 */
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
