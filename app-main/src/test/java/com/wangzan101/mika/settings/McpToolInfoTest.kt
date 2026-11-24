package com.wangzan101.mika.settings

import com.wangzan101.mika.core.cap.mcp.McpToolDescriptor
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 工具列表页靠这两项展示:名字照原样,说明去掉首尾空白(很多 server 会给换行缩进)。 */
class McpToolInfoTest {
    private fun descriptor(name: String, description: String) = McpToolDescriptor(
        name = name,
        description = description,
        inputSchema = buildJsonObject {},
    )

    @Test
    fun keepsTheNameAndTrimsTheDescription() {
        val info = McpToolInfo.of(descriptor("search_docs", "  Search the documentation.\n\n"))
        assertEquals("search_docs", info.name)
        assertEquals("Search the documentation.", info.description)
    }

    @Test
    fun missingDescriptionStaysEmpty() {
        assertEquals("", McpToolInfo.of(descriptor("ping", "")).description)
    }
}
