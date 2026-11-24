package com.wangzan101.mika.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLoansTest {
    /** 任务一侧:记下做了什么;[accept]/[adopts] 决定借不借得进、收不收下。 */
    private class Task(var accept: Boolean = true, var adopts: Boolean = true) : PageLoans.Borrower<String> {
        val log = mutableListOf<String>()
        override fun take(tab: String) = accept.also { log += "take $tab" }
        override fun giveBack(tab: String) { log += "give $tab" }
        override fun swap(prev: String, next: String) { log += "swap $prev>$next" }
        override fun adopt(tab: String) = adopts.also { log += "adopt $tab" }
        override fun touched() { log += "touch" }
    }

    @Test fun offeredPageGoesToThatConversationsTaskOnly() {
        val loans = PageLoans<String>()
        assertNull(loans.claim("c1", "r1", "tab"))
        loans.offer("c1")
        assertNull(loans.claim("c2", "r2", "tab"))
        assertNull(loans.claim("c1", "r1", null))
        val l = loans.claim("c1", "r1", "tab")!!
        assertEquals("c1", loans.lentTo.value)
        assertNull(loans.offeredTo)
        // 已经借出:别的任务、同一对话再来都借不到(答应的已用掉)
        loans.offer("c1")
        assertNull(loans.claim("c1", "r3", "tab"))
        val t = Task()
        assertTrue(loans.bind(l, t))
        assertEquals(listOf("take tab"), t.log)
    }

    @Test fun taskEndReturnsThePage() {
        val loans = PageLoans<String>()
        loans.offer("c1")
        val l = loans.claim("c1", "r1", "tab")!!
        val t = Task()
        loans.bind(l, t)
        loans.end("other")
        assertSame(l, loans.current)
        loans.end("r1")
        assertNull(loans.current)
        assertNull(loans.lentTo.value)
        assertEquals(listOf("take tab", "give tab"), t.log)
        // 还回来之后摸页面、关页面都和任务无关
        loans.touched("tab")
        assertFalse(loans.closing("tab"))
        assertEquals(2, t.log.size)
    }

    @Test fun loanFollowsTheVisibleTabAndCountsTouches() {
        val loans = PageLoans<String>()
        loans.offer("c1")
        val l = loans.claim("c1", "r1", "page")!!
        val t = Task()
        loans.bind(l, t)
        loans.swapped("page", "popup")
        assertEquals("popup", l.tab)
        loans.touched("page")
        loans.touched("popup")
        loans.touched("popup")
        assertEquals(2, l.touches)
        loans.swapped("other", "x")
        assertEquals(listOf("take page", "swap page>popup", "touch", "touch"), t.log)
    }

    @Test fun closingHandsThePageToTheTaskOrLetsTheBrowserDestroyIt() {
        val loans = PageLoans<String>()
        loans.offer("c1")
        val kept = Task(adopts = true)
        loans.bind(loans.claim("c1", "r1", "tab")!!, kept)
        assertTrue(loans.closing("tab"))
        assertNull(loans.current)
        assertEquals(listOf("take tab", "adopt tab"), kept.log)

        loans.offer("c2")
        val refused = Task(adopts = false)
        loans.bind(loans.claim("c2", "r2", "tab2")!!, refused)
        assertFalse(loans.closing("tab2"))
        assertEquals(listOf("take tab2", "adopt tab2", "give tab2"), refused.log)
    }

    @Test fun closingBeforeTheTaskIsReadyCancelsTheLoan() {
        val loans = PageLoans<String>()
        loans.offer("c1")
        val l = loans.claim("c1", "r1", "tab")!!
        // 任务还没接上,页面就关了:浏览器页自己关掉它
        assertFalse(loans.closing("tab"))
        val t = Task()
        assertFalse(loans.bind(l, t))
        assertEquals(emptyList<String>(), t.log)
        // 答应了还没取走时关页面:答应的作废
        loans.offer("c2")
        loans.closing("other")
        assertNull(loans.claim("c2", "r2", "tab"))
    }

    @Test fun refusedTakeEndsTheLoan() {
        val loans = PageLoans<String>()
        loans.offer("c1")
        val l = loans.claim("c1", "r1", "tab")!!
        assertFalse(loans.bind(l, Task(accept = false)))
        assertNull(loans.current)
        assertNull(loans.lentTo.value)
    }
}
