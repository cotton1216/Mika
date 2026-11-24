package com.wangzan101.mika.browser

import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.task.TaskGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PageAskModelTest {
    @get:Rule val texts = ChineseTexts()

    private val running = TaskState.Running(1, "x")
    private val paused get() = TaskState.Held(TaskGate.paused(TaskGate.USER_ON_PAGE))
    private val asking = TaskState.WaitingInput("哪个颜色?", listOf("黑", "白"))

    @Test fun barPanelOrCard() {
        assertEquals(PageAskMode.Bar, PageAskModel.mode(null, open = false))
        assertEquals(PageAskMode.Panel, PageAskModel.mode(null, open = true))
        assertEquals(PageAskMode.Card, PageAskModel.mode(running, open = false))
        assertEquals(PageAskMode.Card, PageAskModel.mode(paused, open = false))
        assertEquals(PageAskMode.Panel, PageAskModel.mode(running, open = true))
        // 结束了又收起来:回到输入条
        assertEquals(PageAskMode.Bar, PageAskModel.mode(TaskState.Cancelled, open = false))
    }

    @Test fun recognisesOnlyThePauseForTheUser() {
        assertTrue(PageAskModel.pausedForUser(paused))
        assertFalse(PageAskModel.pausedForUser(TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY))))
        assertFalse(PageAskModel.pausedForUser(TaskState.Held(GateReason("waiting_unlock"))))
        assertFalse(PageAskModel.pausedForUser(running))
    }

    @Test fun glowsOnlyWhileActing() {
        assertTrue(PageAskModel.glow(running))
        assertFalse(PageAskModel.glow(paused))
        assertFalse(PageAskModel.glow(asking))
        assertFalse(PageAskModel.glow(null))
    }

    @Test fun panelCollapsesWhenYouTakeOverAndOpensWhenMikaNeedsYou() {
        assertEquals(false, PageAskModel.follow(running, paused))
        assertNull(PageAskModel.follow(paused, paused))
        assertEquals(true, PageAskModel.follow(running, asking))
        assertNull(PageAskModel.follow(asking, asking))
        assertNull(PageAskModel.follow(paused, running))
        assertEquals(true, PageAskModel.follow(null, TaskState.Held(TaskGate.userStep("选一下"))))
    }
}
