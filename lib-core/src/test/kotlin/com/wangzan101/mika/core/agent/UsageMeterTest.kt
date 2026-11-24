package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.testing.TOOLS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageMeterTest {
    private val task = Message(Role.User, listOf(Part.Text("帮我查天气")), Author.User)
    private var seq = 0
    private fun call() = Message(Role.Assistant, listOf(Part.ToolCall("c${++seq}", "phone_tap", """{"target":"按钮"}""")), Author.Mika)
    private fun result(pad: Int = 400) = Message(Role.Tool, listOf(Part.ToolResult("c$seq", """{"ok":true,"pad":"${"x".repeat(pad)}"}""", false)), Author.Mika)

    @Test fun noUsageYetEstimatesMessagesAndOverheadOnce() {
        val h = listOf(task, call(), result())
        // 第一步:没有锚点,消息 + 固定开销一起估,乘同一个比值
        assertEquals(UsageMeter.estimate(TokenEstimate.of(h) + 11_000, 1.2), UsageMeter.of(h, 11_000, 1.2))
    }

    @Test fun anchorsOnTheLatestReplyWithUsage() {
        val first = call()
        val r1 = result()
        val second = call()
        val r2 = result()
        val h = listOf(task, first, r1, second, r2)
        val usage = mapOf(first to UsageMeter.Anchor(Usage(10_000, 50), 9_000), second to UsageMeter.Anchor(Usage(12_000, 40), 9_000))
        // 真实输入(已含当时的提示词和工具)+ 回复的输出 + 之后消息的估算;固定开销没变,不再加
        assertEquals(12_000 + 40 + UsageMeter.estimate(TokenEstimate.of(listOf(r2)), 1.0), UsageMeter.of(h, 9_000, 1.0, usage::get))
        // 之后固定开销变了(展开了一组工具):只加变化的那部分
        assertEquals(12_000 + 40 + UsageMeter.estimate(TokenEstimate.of(listOf(r2)) + 500, 1.0), UsageMeter.of(h, 9_500, 1.0, usage::get))
    }

    @Test fun thinkingTokensAreNotContextGrowth() {
        val reply = call()
        val h = listOf(task, reply)
        // 输出 3000 里 2900 是服务商单独报出的思考,只有 100 会作为下一次的输入再发
        val anchor = UsageMeter.Anchor(Usage(inputTokens = 8_000, outputTokens = 3_000, reasoningTokens = 2_900), 0)
        assertEquals(8_100, UsageMeter.of(h, 0, 1.0) { if (it === reply) anchor else null })
        assertEquals(0, UsageMeter.resent(Usage(1, 10, reasoningTokens = 50)))
    }

    @Test fun anchoredOverheadIsNotCountedTwiceInTheTrigger() {
        // 32K 的本地模型,提示词和工具 11K:真实用量已含这 11K,触发点不再扣一次,不会每步都压
        val window = 32_000
        val overhead = 11_000
        val c = ContextCompressor(TOOLS)
        val h = mutableListOf(task)
        var input = 12_000
        var compactions = 0
        repeat(10) {
            val reply = call()
            h += reply
            h += result(pad = 200)
            val anchor = UsageMeter.Anchor(Usage(input, 30), overhead)
            val size = UsageMeter.of(h, overhead, c.scale) { if (it === reply) anchor else null }
            if (c.compress(h, window, size, fixedOverhead = overhead).foldedSteps > 0) compactions++
            input += 100
        }
        assertEquals(0, compactions)
        // 越过触发点(18K)才压
        val reply = call()
        h += reply
        h += result()
        val over = UsageMeter.of(h, overhead, c.scale) { if (it === reply) UsageMeter.Anchor(Usage(18_500, 30), overhead) else null }
        assertTrue(over > ContextLimits.of(window).trigger)
        assertTrue(c.compress(h, window, over, fixedOverhead = overhead).messages != h)
    }

    @Test fun firstStepWithHeavyOverheadOnSmallWindowDoesNotCompactEveryStep() {
        // 没有真实用量,固定开销已过触发点:压一次之后有回差,不会每一步都重新压
        val window = 32_000
        val overhead = 16_000
        val c = ContextCompressor(TOOLS)
        val h = mutableListOf(task)
        var rewrites = 0
        repeat(30) {
            h += call()
            h += result()
            if (c.compress(h, window, fixedOverhead = overhead).rewritten) rewrites++
        }
        assertTrue("$rewrites", rewrites in 1..3)
    }
}
