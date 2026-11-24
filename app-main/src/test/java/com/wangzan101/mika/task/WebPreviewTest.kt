package com.wangzan101.mika.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPreviewTest {
    // 只在后台临时标签里读网页:网页显示上没有标签,不算在网页上,小窗不出来(以前会是一块白底)
    @Test fun backgroundReadsAloneDoNotOpenThePreview() {
        val p = WebPreview.of(onWeb = true, hasTab = false, handedOver = false)
        assertEquals(WebPreview.NoPage, p)
        assertFalse(p.open)
    }

    // 打开了看得见的页面:放画面
    @Test fun aVisibleTabShowsItsFrames() {
        val p = WebPreview.of(onWeb = true, hasTab = true, handedOver = false)
        assertEquals(WebPreview.Frames, p)
        assertTrue(p.open)
    }

    // 页面在用户手上:还算在网页上,不放画面
    @Test fun handedOverPageStaysOpenWithoutFrames() {
        val p = WebPreview.of(onWeb = true, hasTab = true, handedOver = true)
        assertEquals(WebPreview.Hidden, p)
        assertTrue(p.open)
    }

    @Test fun notOnTheWebIsOff() {
        assertEquals(WebPreview.Off, WebPreview.of(onWeb = false, hasTab = true, handedOver = false))
        assertEquals(WebPreview.Off, WebPreview.of(onWeb = false, hasTab = false, handedOver = true))
    }
}
