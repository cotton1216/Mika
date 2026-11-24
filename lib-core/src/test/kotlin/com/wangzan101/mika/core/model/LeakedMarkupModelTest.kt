package com.wangzan101.mika.core.model

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 端到端:服务商把工具调用标记写在 content 里(而不是 tool_calls)时,
 * 适配器要把它还原成真正的工具调用,界面上不能出现标记本身。
 */
class LeakedMarkupModelTest {
    private lateinit var server: MockWebServer
    private val caps = Capabilities(true, false, 32000, false)
    private val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.User))

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun model() = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m", caps, OkHttpClient())
    private fun event(data: String) = "data: $data\n\n"

    private val leaked = "<｜｜DSML｜｜ calls>" +
        "<｜｜DSML｜｜ invoke name=\"phone_observe\">" +
        "<｜｜DSML｜｜ parameter name=\"scope\" string=\"true\">screen</｜｜DSML｜｜ parameter>" +
        "</｜｜DSML｜｜ invoke></｜｜DSML｜｜ calls>"

    private fun body(content: String) = buildJsonObject {
        putJsonArray("choices") {
            add(buildJsonObject {
                put("finish_reason", "stop")
                putJsonObject("message") { put("content", content) }
            })
        }
    }.toString()

    @Test fun nonStreamingContentWithDsmlBecomesToolCall() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body("我先看一眼$leaked")))
        val reply = model().complete("s", user, listOf(ToolSpec("phone_observe", "d", """{"type":"object"}""")))
        val call = reply.parts.filterIsInstance<Part.ToolCall>().single()
        assertEquals("phone_observe", call.name)
        assertEquals("screen", kotlinx.serialization.json.Json.parseToJsonElement(call.argsJson)
            .let { (it as kotlinx.serialization.json.JsonObject)["scope"]!!.toString().trim('"') })
        assertEquals(StopReason.ToolUse, reply.stopReason)
        val text = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        assertEquals("我先看一眼", text)
    }

    @Test fun streamingNeverShowsMarkupAndRecoversCall() = runTest {
        val chunks = listOf(
            "我先",
            "查一下<｜｜DSML｜｜ invoke name=\"phone_observe\">",
            "<｜｜DSML｜｜ parameter name=\"scope\" string=\"true\">screen</｜｜DSML｜｜ parameter>",
            "</｜｜DSML｜｜ invoke>",
        )
        val data = chunks + listOf("[DONE]")
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(data.joinToString("") { event(if (it == "[DONE]") it else chunkJson(it)) }),
        )
        val updates = mutableListOf<String>()
        val reply = model().completeStreaming("s", user, listOf(ToolSpec("phone_observe", "d", "{}")), updates::add)
        assertTrue(updates.none { it.contains("DSML") || it.contains("invoke") })
        assertEquals("phone_observe", reply.parts.filterIsInstance<Part.ToolCall>().single().name)
        assertEquals(StopReason.ToolUse, reply.stopReason)
        val text = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        assertFalse(text.contains("DSML"))
        assertEquals("我先查一下", text)
    }

    private fun chunkJson(text: String) = buildJsonObject {
        putJsonArray("choices") { add(buildJsonObject { putJsonObject("delta") { put("content", text) } }) }
    }.toString()
}
