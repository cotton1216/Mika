package com.wangzan101.mika.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LeakedToolCallTest {
    // 和真实工具列表一样,把 wait、find 这种也是常用词的工具名排在前面
    private val tools = listOf("wait", "find", "ask_user", "web_read_page", "phone_observe")

    /** XML 写法的调用,服务端没解析,原样成了正文。 */
    @Test fun xmlStyleCallIsFound() {
        val text = "<tool_call>\n<function=ask_user>\n<parameter=question>\n想先确认一下:是要开户,还是了解集合竞价?\n</parameter>\n</function>\n</tool_call>"
        assertEquals("ask_user", LeakedToolCall.find(text, tools))
    }

    @Test fun jsonStyleCallIsFound() {
        val text = "<tool_call>\n{\"name\": \"web_read_page\", \"arguments\": {}}\n</tool_call>"
        assertEquals("web_read_page", LeakedToolCall.find(text, tools))
    }

    @Test fun otherMarkersAreFound() {
        assertEquals("phone_observe", LeakedToolCall.find("[TOOL_CALLS][{\"name\": \"phone_observe\", \"arguments\": {}}]", tools))
        assertEquals("phone_observe", LeakedToolCall.find("<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>function<｜tool▁sep｜>phone_observe", tools))
    }

    /** 用户问的就是这些格式:回答里提到标记,但没提到这次的任何工具,是正常回答。 */
    @Test fun markupWithoutAKnownToolIsNotALeak() {
        assertNull(LeakedToolCall.find("有的模型写工具调用时用 `<tool_call>` 包起来,里面是 `<function=名字>`。", tools))
    }

    /** 回答里提到工具名(例如说明做过什么),没有调用标记,也不算。 */
    @Test fun toolNameWithoutMarkupIsNotALeak() {
        assertNull(LeakedToolCall.find("我用 phone_observe 看过屏幕,安卓版本是 14。", tools))
    }

    /** 标记前面的话里出现和工具同名的常用词(wait、find)不算:报的是标记后面真正被调用的那个工具。 */
    @Test fun toolNameIsTakenAfterTheMarker() {
        val text = "Wait, let me find the page first.\n<tool_call>\n<function=web_read_page>\n</function>\n</tool_call>"
        assertEquals("web_read_page", LeakedToolCall.find(text, tools))
    }

    /** 工具名只是别的词的一部分时不算提到了这个工具。 */
    @Test fun toolNameMustBeAWholeWord() {
        assertNull(LeakedToolCall.find("<tool_call>{\"name\": \"ask_user_later\"}</tool_call>", tools))
        assertNull(LeakedToolCall.find("<tool_call>{\"name\": \"my_ask_user\"}</tool_call>", tools))
    }
}
