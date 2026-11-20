package com.xxt.zl.ai.ruyiremote.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T15 单测：markdown 里的 `![alt](path)` 相对图片路径识别。
 * 只放行简单相对图片文件名，拒绝绝对路径 / 目录遍历 / 盘符 / 控制字符；
 * http(s) 图片不自动下载，按普通文本（只留 alt）处理。
 */
class MarkdownInlineTest {

    @Test
    fun `workspace image path accepts simple relative image names`() {
        assertTrue(isWorkspaceImagePath("reading-handwritten-newspaper.png"))
        assertTrue(isWorkspaceImagePath("a.jpg"))
        assertTrue(isWorkspaceImagePath("sub/dir/pic.webp"))
        assertTrue(isWorkspaceImagePath("photo.JPEG"))
        assertTrue(isWorkspaceImagePath("anim.gif"))
    }

    @Test
    fun `workspace image path rejects traversal absolute and non image paths`() {
        assertFalse(isWorkspaceImagePath(""))
        assertFalse(isWorkspaceImagePath("/tmp/a.png"))
        assertFalse(isWorkspaceImagePath("../a.png"))
        assertFalse(isWorkspaceImagePath("sub/../a.png"))
        assertFalse(isWorkspaceImagePath("a\\b.png"))
        assertFalse(isWorkspaceImagePath("C:a.png"))
        assertFalse(isWorkspaceImagePath("a b.png"))
        assertFalse(isWorkspaceImagePath("a.txt"))
        assertFalse(isWorkspaceImagePath("a.png\n"))
        assertFalse(isWorkspaceImagePath("x".repeat(513)))
    }

    @Test
    fun `parse inline turns markdown image into workspace image reference`() {
        // 真机实证的手抄报格式：![书香伴我成长手抄报](<reading-handwritten-newspaper.png>)
        val spans = parseMarkdownInline("![书香伴我成长手抄报](<reading-handwritten-newspaper.png>)")
        assertEquals(1, spans.size)
        assertEquals("书香伴我成长手抄报", spans[0].text)
        assertEquals("reading-handwritten-newspaper.png", spans[0].imagePath)
        assertNull(spans[0].url)
    }

    @Test
    fun `parse inline keeps surrounding text and marks only the image`() {
        val spans = parseMarkdownInline("前面 ![图](a.png) 后面")
        assertEquals(3, spans.size)
        assertEquals("前面 ", spans[0].text)
        assertNull(spans[0].imagePath)
        assertEquals("图", spans[1].text)
        assertEquals("a.png", spans[1].imagePath)
        assertEquals(" 后面", spans[2].text)
        assertNull(spans[2].imagePath)
    }

    @Test
    fun `parse inline does not auto load http images or unsafe paths`() {
        // http(s) 图片：不自动下载远程资源，只保留可读 alt。
        val remote = parseMarkdownInline("![x](https://example.com/a.png)")
        assertEquals(1, remote.size)
        assertEquals("x", remote[0].text)
        assertNull(remote[0].imagePath)

        // 目录遍历路径：不合法，退回普通文本。
        val traversal = parseMarkdownInline("![x](../a.png)")
        assertEquals(1, traversal.size)
        assertNull(traversal[0].imagePath)
    }

    @Test
    fun `parse inline leaves plain links and bold unchanged`() {
        // 普通链接不受图片解析影响（不是安全 url，保留标签、不填 imagePath）。
        val link = parseMarkdownInline("[名字](not-a-url)")
        assertEquals(1, link.size)
        assertNull(link[0].imagePath)

        val bold = parseMarkdownInline("**粗体** ![图](p.png)")
        assertTrue(bold.any { it.bold && it.text == "粗体" && it.imagePath == null })
        assertTrue(bold.any { it.text == "图" && it.imagePath == "p.png" })
    }
}
