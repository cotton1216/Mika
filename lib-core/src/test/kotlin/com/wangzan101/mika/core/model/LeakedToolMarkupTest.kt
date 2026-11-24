package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型把工具调用标记吐进正文时的清洗与还原。
 * 样本按实际见过的泄漏输出(双竖线 DSML、调用名 memory_write,一个不存在的工具)。
 */
class LeakedToolMarkupTest {
    private val bars = "｜｜"
    private val dsml = "<${bars}DSML${bars} calls>" +
        "<${bars}DSML${bars} invoke name=\"memory_write\">" +
        "<${bars}DSML${bars} parameter name=\"content\" string=\"true\">到手价=韩元×0.9160125-2681.25</${bars}DSML${bars} parameter>" +
        "<${bars}DSML${bars} parameter name=\"reason\" string=\"true\">用户要求把公式写进记忆</${bars}DSML${bars} parameter>" +
        "</${bars}DSML${bars} invoke></${bars}DSML${bars} calls>"

    @Test fun recoversDoubledPipeDsmCalls() {
        val scan = LeakedToolMarkup.scan(dsml)
        assertEquals(1, scan.calls.size)
        assertEquals("memory_write", scan.calls[0].name)
        assertEquals("", scan.text)
        val args = Json.parseToJsonElement(scan.calls[0].argsJson).jsonObject
        assertEquals("到手价=韩元×0.9160125-2681.25", args["content"]!!.jsonPrimitive.content)
        assertEquals("用户要求把公式写进记忆", args["reason"]!!.jsonPrimitive.content)
    }

    @Test fun recoversCanonicalSinglePipeAndJsonParameter() {
        val text = "<｜DSML｜invoke name=\"phone_tap\">" +
            "<｜DSML｜parameter name=\"ref\" string=\"true\">a1</｜DSML｜parameter>" +
            "<｜DSML｜parameter name=\"count\" string=\"false\">3</｜DSML｜parameter>" +
            "</｜DSML｜invoke>"
        val scan = LeakedToolMarkup.scan(text)
        assertEquals("phone_tap", scan.calls.single().name)
        val args = Json.parseToJsonElement(scan.calls.single().argsJson).jsonObject
        assertEquals("a1", args["ref"]!!.jsonPrimitive.content)
        assertEquals("3", args["count"]!!.jsonPrimitive.content)
    }

    @Test fun stripsWrapperOnlyMarkup() {
        val scan = LeakedToolMarkup.scan("<${bars}DSML${bars} calls>\n<${bars}DSML${bars} function_calls>\n今天的天气不错")
        assertTrue(scan.calls.isEmpty())
        assertEquals("今天的天气不错", scan.text)
    }

    @Test fun keepsTextAroundTheMarkup() {
        val text = "先说一句\n$dsml\n后面还有一句"
        val scan = LeakedToolMarkup.scan(text)
        assertEquals(1, scan.calls.size)
        assertTrue(scan.text.contains("先说一句"))
        assertTrue(scan.text.contains("后面还有一句"))
        assertFalse(scan.text.contains("DSML"))
    }

    @Test fun recoversJsonToolCallBlock() {
        val text = "<tool_call>\n{\"name\":\"phone_tap\",\"arguments\":{\"ref\":\"a1\"}}\n</tool_call>"
        val scan = LeakedToolMarkup.scan(text)
        assertEquals("phone_tap", scan.calls.single().name)
        assertEquals("{\"ref\":\"a1\"}", scan.calls.single().argsJson)
        assertEquals("", scan.text)
    }

    @Test fun plainTextIsUntouched() {
        val text = "2 < 3，今天天气不错，用 <b> 标签也行"
        val scan = LeakedToolMarkup.scan(text)
        assertEquals(text, scan.text)
        assertTrue(scan.calls.isEmpty())
        assertEquals(text, LeakedToolMarkup.stripMarkup(text))
    }

    /** 正文里有 `<` 又提到 DSML 这个词,但不是标记:一个字也不剥。 */
    @Test fun textMentioningDsmlAfterALessThanIsUntouched() {
        val text = "比较 a<b 时,顺便说说 DSML 这种写法"
        assertEquals(text, LeakedToolMarkup.stripMarkup(text))
        assertEquals(text, LeakedToolMarkup.scan(text).text)
    }

    /** 收不全的半个结束标记也不显示。 */
    @Test fun partialClosingTagIsHiddenInPreview() {
        assertEquals("我查一下", LeakedToolMarkup.stripMarkup("我查一下</${bars}DSM"))
    }

    /** `<tool_call>` 里不是合法 JSON(例如 XML 写法):原样留着,交给 AgentLoop 的漏调用检查,不剥成只剩前面一句。 */
    @Test fun unparsableToolCallBlockIsKept() {
        val text = "我查一下\n<tool_call>\n<function=phone_observe>\n</function>\n</tool_call>"
        val reply = ModelReply(listOf(Part.Text(text)), StopReason.EndTurn, null)
        assertEquals(reply, LeakedToolMarkup.repair(reply))
    }

    @Test fun repairTurnsLeakedMarkupIntoToolUse() {
        val reply = ModelReply(listOf(Part.Text(dsml)), StopReason.EndTurn, null)
        val fixed = LeakedToolMarkup.repair(reply)
        assertEquals(StopReason.ToolUse, fixed.stopReason)
        assertTrue(fixed.parts.none { it is Part.Text })
        val call = fixed.parts.filterIsInstance<Part.ToolCall>().single()
        assertEquals("memory_write", call.name)
        assertTrue(call.id.isNotEmpty())
    }

    @Test fun repairLeavesNormalReplyAlone() {
        val reply = ModelReply(listOf(Part.Text("好的，已经查到了")), StopReason.EndTurn, null)
        assertEquals(reply, LeakedToolMarkup.repair(reply))
    }

    @Test fun repairKeepsThinkingAndTextAlongsideRecoveredCall() {
        val reply = ModelReply(
            listOf(Part.Thinking("想一想"), Part.Text("我查一下\n$dsml"), Part.Text("等一下")),
            StopReason.EndTurn, null,
        )
        val fixed = LeakedToolMarkup.repair(reply)
        assertEquals(StopReason.ToolUse, fixed.stopReason)
        assertTrue(fixed.parts.contains(Part.Thinking("想一想")))
        val text = fixed.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        assertTrue(text.contains("我查一下"))
        assertTrue(text.contains("等一下"))
        assertFalse(text.contains("DSML"))
        assertEquals(1, fixed.parts.filterIsInstance<Part.ToolCall>().size)
    }

    @Test fun streamingPreviewNeverShowsMarkup() {
        // 流式预览按累计文字逐帧清洗,标记落地前的那一帧也不能带出乱码
        val frames = listOf(
            "我查一下\n<${bars}DSML",
            "我查一下\n$dsml",
        )
        for (f in frames) assertFalse(LeakedToolMarkup.stripMarkup(f).contains("DSML"))
    }
}
