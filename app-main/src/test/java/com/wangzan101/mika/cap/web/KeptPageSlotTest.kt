package com.wangzan101.mika.cap.web

import com.wangzan101.mika.core.cap.web.KeptPageClose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 留页的纯逻辑:留下、留新的顶掉旧的、到 2 小时自动关(假时钟)、各种关法的原因、关的时候在来源对话那一轮记一句。 */
class KeptPageSlotTest {
    private var now = 1_000L
    private val released = mutableListOf<String>()
    private val notes = mutableListOf<Triple<String, String?, KeptPageClose>>()
    private val slot = KeptPageSlot<String>({ now }, release = { released += it }, onClosed = { p, r -> notes += Triple(p.conversationId, p.runId, r) })

    private fun keep(handle: String, conversation: String = "c1", run: String = "r1") =
        slot.keep(handle, KeptOrigin(conversation, run, "把这门课点开,挂着别关"), "course.example.com", "https://course.example.com/3", "第 3 节")

    @Test fun keepsOnePageForTwoHours() {
        val p = keep("tab1")
        assertEquals(p, slot.page.value)
        assertEquals(now + 2 * KeptPageSlot.HOUR_MS, p.closesAt)
        assertEquals("tab1", slot.handle())
        assertEquals(2 * KeptPageSlot.HOUR_MS, slot.remainingMs())
        // 没到点不关
        now += 2 * KeptPageSlot.HOUR_MS - 1
        assertFalse(slot.expireIfDue())
        assertEquals(1L, slot.remainingMs())
        // 到点自动关,放掉手里的东西,在来源那一轮记下原因
        now += 1
        assertTrue(slot.expireIfDue())
        assertNull(slot.page.value)
        assertNull(slot.handle())
        assertEquals(listOf("tab1"), released)
        assertEquals(listOf(Triple("c1", "r1", KeptPageClose.Expired)), notes)
        assertFalse(slot.expireIfDue())
    }

    @Test fun keepingAnotherPageClosesTheOldOne() {
        val first = keep("tab1", "c1", "r1")
        val second = keep("tab2", "c2", "r2")
        assertTrue(second.id != first.id)
        assertEquals(listOf("tab1"), released)
        assertEquals(listOf(Triple("c1", "r1", KeptPageClose.Replaced)), notes)
        assertEquals("c2", slot.page.value?.conversationId)
        assertEquals("tab2", slot.handle())
    }

    @Test fun closeReasonsAreReportedOnce() {
        for (reason in listOf(KeptPageClose.User, KeptPageClose.Memory, KeptPageClose.Crashed, KeptPageClose.AppExit)) {
            keep("tab-$reason")
            assertTrue(slot.close(reason))
            assertFalse(slot.close(reason))
        }
        assertEquals(listOf(KeptPageClose.User, KeptPageClose.Memory, KeptPageClose.Crashed, KeptPageClose.AppExit), notes.map { it.third })
        assertEquals(4, released.size)
    }

    @Test fun lateCallbackDoesNotCloseALaterPage() {
        val old = keep("tab1")
        keep("tab2")
        // 旧那一页崩溃的回调晚到:不关后来留的那一页
        assertFalse(slot.closeIf(old.id, KeptPageClose.Crashed))
        assertEquals("tab2", slot.handle())
    }

    @Test fun recordForTheTurnEvenAfterItClosed() {
        assertNull(slot.recordFor("r1"))
        keep("tab1", run = "r1")
        assertEquals("course.example.com" to null, slot.recordFor("r1"))
        assertNull(slot.recordFor("r2"))
        assertNull(slot.recordFor(null))
        // 那一轮还没写进对话页面就崩了:照样记得留过、为什么关
        slot.close(KeptPageClose.Crashed)
        assertEquals("course.example.com" to "crashed", slot.recordFor("r1"))
    }
}
