package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {
    private fun text(turns: List<Turn>, maxTurns: Int = 5, maxTokens: Int = 2_500) =
        (Conversation.context(turns, maxTurns, maxTokens)!!.parts.single() as Part.Text).text

    @Test fun noTurnsNoMessage() {
        assertNull(Conversation.context(emptyList()))
    }

    @Test fun recentTurnsLastAndTruncated() {
        val turns = (1..8).map { Turn("问题$it", "答案$it", "succeeded") }
        val m = Conversation.context(turns, maxTurns = 3)!!
        assertEquals(Role.User, m.role)
        val t = (m.parts.single() as Part.Text).text
        assertFalse("问题5" in t)
        assertTrue(t.indexOf("问题6") < t.indexOf("问题7") && t.indexOf("问题7") < t.indexOf("问题8"))
        // 超长时丢掉较早的,保留最近的
        val long = (1..5).map { Turn("问题$it", "长".repeat(300), "succeeded") }
        val capped = text(long, maxTokens = 600)
        assertTrue(TokenEstimate.of(capped) <= 600)
        assertTrue("问题5" in capped && "问题1" !in capped)
        // 单轮就超长:截断而不是超出
        assertTrue(TokenEstimate.of(text(listOf(Turn("问", "长".repeat(5000), "succeeded")), maxTokens = 300)) <= 300)
        // 各字段分别截:原话很长时结局也还在
        val both = text(listOf(Turn("问".repeat(2000), "长".repeat(5000), "succeeded")), maxTokens = 300)
        assertTrue(both, TokenEstimate.of(both) <= 300 && "The user said: 问问" in both && "result: 长长" in both)
    }

    @Test fun keptPageSaysWhetherItIsStillOpen() {
        val open = text(listOf(Turn("播放课程,挂着别关", "已开始播放", "succeeded", keptPage = "course.example.com")))
        assertTrue("kept the page on course.example.com running in the background" in open)
        val closed = text(listOf(Turn("播放课程", "已开始播放", "succeeded", keptPage = "course.example.com", keptPageClosed = "expired")))
        assertTrue("has since been closed" in closed)
        assertFalse("course.example.com" in text(listOf(Turn("播放课程", "已开始播放", "succeeded"))))
    }

    @Test fun failedTurnShowsReason() {
        // 停止的那一轮没有交代时只说取消了(停止后补上交代的见 StopSummaryTest)
        val t = text(listOf(Turn("订票", "验证码一直过不去", "failed"), Turn("查天气", Conversation.CANCELLED_OUTCOME, "cancelled")))
        assertTrue("not finished, reason: 验证码一直过不去" in t)
        assertTrue("the user cancelled this task" in t && "before the stop" !in t)
    }

    @Test fun unverifiedTurnIsLabelled() {
        val t = text(listOf(Turn("查余额", "余额 10 元", "unverified", note = "防截屏")))
        assertTrue(t, "result (could not be verified): 余额 10 元" in t)
    }
}
