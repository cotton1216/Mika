package com.wangzan101.mika.browser

import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.chat.PageOrigin
import com.wangzan101.mika.core.cap.web.WebUrlPolicy

/**
 * 在浏览器页上对着一页问如愿时,接着哪个对话(纯逻辑)。规则:
 * 1. 同一页(网址相同,不计 `#` 后面)上问过的,接着最近那个对话,不论隔了多久;
 * 2. 否则同一个网站(主机名相同)上 [SAME_SITE_MS] 内问过的,接着最近那个——在一个网站里一路点下去(搜索结果 → 商品页 → 购物车)
 *    还是同一件事;
 * 3. 都没有就开新对话。用户随时可以选「新对话」。
 * 隔了很久再来同一个网站的另一页,多半是另一件事,所以第 2 条有时限;同一页没有时限,回到这一页就是接着这一页的事。
 */
object PageConversations {
    /** 同一个网站上「接着问」的时限:最近一次改动在这之内。 */
    const val SAME_SITE_MS = 2 * 60 * 60 * 1000L

    /** [all] 是对话列表(最近改过的在前,见 [com.wangzan101.mika.chat.ConversationStore.list]);[url] 是此刻看着的页面。 */
    fun pick(all: List<ConversationSummary>, url: String, now: Long): ConversationSummary? {
        val page = pageKey(url)
        val host = WebUrlPolicy.host(url)
        val fromPages = all.filter { it.page != null }.sortedByDescending { it.updatedAt }
        return fromPages.firstOrNull { pageKey(it.page!!.url) == page }
            ?: fromPages.firstOrNull { host != null && it.page!!.host == host && now - it.updatedAt in 0..SAME_SITE_MS }
    }

    /** 这一页记成对话的来处:网址、主机名、标题;不是网页地址为 null。 */
    fun origin(url: String, title: String): PageOrigin? {
        val host = WebUrlPolicy.host(url)?.takeIf { url.startsWith("http", ignoreCase = true) } ?: return null
        return PageOrigin(url, host, title.trim())
    }

    /** 认「同一页」用的网址:去掉 `#` 后面和结尾的斜杠。 */
    fun pageKey(url: String): String = url.substringBefore('#').trimEnd('/')
}
