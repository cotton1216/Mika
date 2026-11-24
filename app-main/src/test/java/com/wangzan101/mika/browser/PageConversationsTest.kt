package com.wangzan101.mika.browser

import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.chat.PageOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageConversationsTest {
    private val now = 1_000_000_000L
    private fun conv(id: String, url: String?, ago: Long) =
        ConversationSummary(id, id, now - ago, page = url?.let { PageConversations.origin(it, "") })

    @Test fun samePageContinuesTheLatestNoMatterHowOld() {
        val all = listOf(
            conv("plain", null, 0),
            conv("old", "https://news.example.org/a/1", 30L * 24 * 3600_000),
            conv("older", "https://news.example.org/a/1", 40L * 24 * 3600_000),
        )
        assertEquals("old", PageConversations.pick(all, "https://news.example.org/a/1#comments", now)?.id)
        assertEquals("old", PageConversations.pick(all, "https://news.example.org/a/1/", now)?.id)
    }

    @Test fun sameSiteContinuesOnlyWithinTheWindow() {
        val recent = conv("recent", "https://shop.example.com/s?k=1", 10 * 60_000)
        val stale = conv("stale", "https://shop.example.com/s?k=2", PageConversations.SAME_SITE_MS + 1)
        assertEquals("recent", PageConversations.pick(listOf(stale, recent), "https://shop.example.com/dp/B2", now)?.id)
        assertNull(PageConversations.pick(listOf(stale), "https://shop.example.com/dp/B2", now))
        // 别的网站不算
        assertNull(PageConversations.pick(listOf(recent), "https://other.example.com/", now))
        // 不是从网页开始的对话不算
        assertNull(PageConversations.pick(listOf(conv("plain", null, 0)), "https://shop.example.com/", now))
    }

    @Test fun samePageBeatsANewerConversationOnTheSameSite() {
        val page = conv("page", "https://shop.example.com/cart", 60 * 60_000)
        val site = conv("site", "https://shop.example.com/s?k=1", 60_000)
        assertEquals("page", PageConversations.pick(listOf(site, page), "https://shop.example.com/cart", now)?.id)
    }

    @Test fun originNeedsAWebAddress() {
        assertEquals(PageOrigin("https://a.example/x", "a.example", "T"), PageConversations.origin("https://a.example/x", " T "))
        assertNull(PageConversations.origin("about:blank", ""))
    }
}
